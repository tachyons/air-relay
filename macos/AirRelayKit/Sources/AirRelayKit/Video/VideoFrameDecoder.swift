import CoreMedia
import Foundation
import VideoToolbox
import os

/// Hardware H.264 decoder for the incoming Android camera stream.
///
/// VIDEO_CONFIG frames carry Annex-B SPS/PPS; VIDEO_FRAME frames carry
/// `pts_us (u64 BE) || flags (u8, bit0 keyframe) || Annex-B NALs`.
/// Decoded CVPixelBuffers are delivered via `onDecodedFrame` (UI preview now;
/// CMIOExtension ring buffer once a signed camera extension ships).
public final class VideoFrameDecoder: @unchecked Sendable {
    private let log = Logger(subsystem: "in.aboobacker.airrelay", category: "VideoDecoder")
    private var formatDescription: CMVideoFormatDescription?
    private var session: VTDecompressionSession?
    private var sps: Data?
    private var pps: Data?

    public var onDecodedFrame: (@Sendable (CVPixelBuffer, CMTime) -> Void)?

    public init() {}

    // MARK: Config (SPS/PPS)

    public func handleConfig(_ annexB: Data) {
        for nal in Self.splitAnnexB(annexB) {
            guard let first = nal.first else { continue }
            switch first & 0x1F {
            case 7: sps = nal
            case 8: pps = nal
            default: break
            }
        }
        guard let sps, let pps else { return }
        var formatDesc: CMVideoFormatDescription?
        let status = sps.withUnsafeBytes { spsPtr in
            pps.withUnsafeBytes { ppsPtr in
                CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 2,
                    parameterSetPointers: [
                        spsPtr.bindMemory(to: UInt8.self).baseAddress!,
                        ppsPtr.bindMemory(to: UInt8.self).baseAddress!,
                    ],
                    parameterSetSizes: [sps.count, pps.count],
                    nalUnitHeaderLength: 4,
                    formatDescriptionOut: &formatDesc
                )
            }
        }
        guard status == noErr, let formatDesc else {
            log.error("Format description failed: \(status)")
            return
        }
        if let existing = formatDescription,
           CMFormatDescriptionEqual(existing, otherFormatDescription: formatDesc) {
            return
        }
        invalidate()
        formatDescription = formatDesc
        createSession(formatDesc)
        log.info("Decoder configured")
    }

    private func createSession(_ formatDesc: CMVideoFormatDescription) {
        let attrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferMetalCompatibilityKey: true,
        ]
        var session: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: formatDesc,
            decoderSpecification: nil,
            imageBufferAttributes: attrs as CFDictionary,
            outputCallback: nil,
            decompressionSessionOut: &session
        )
        guard status == noErr, let session else {
            log.error("VTDecompressionSession create failed: \(status)")
            return
        }
        self.session = session
    }

    // MARK: Frames

    public func handleFrame(_ payload: Data) {
        guard payload.count > 9, let session, let formatDescription else { return }
        let ptsUs = payload.prefix(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        let pts = CMTime(value: CMTimeValue(ptsUs), timescale: 1_000_000)
        let annexB = payload.dropFirst(9)

        // Annex-B -> AVCC (4-byte length prefix per NAL)
        var avcc = Data()
        for nal in Self.splitAnnexB(Data(annexB)) {
            var length = UInt32(nal.count).bigEndian
            withUnsafeBytes(of: &length) { avcc.append(contentsOf: $0) }
            avcc.append(nal)
        }
        guard !avcc.isEmpty else { return }

        var blockBuffer: CMBlockBuffer?
        let allocStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: avcc.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: avcc.count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard allocStatus == noErr, let blockBuffer else { return }
        avcc.withUnsafeBytes { ptr in
            _ = CMBlockBufferReplaceDataBytes(
                with: ptr.baseAddress!,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: avcc.count
            )
        }

        var sampleBuffer: CMSampleBuffer?
        var timing = CMSampleTimingInfo(
            duration: .invalid, presentationTimeStamp: pts, decodeTimeStamp: .invalid
        )
        var sampleSize = avcc.count
        let sampleStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: formatDescription,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard sampleStatus == noErr, let sampleBuffer else { return }

        VTDecompressionSessionDecodeFrame(
            session,
            sampleBuffer: sampleBuffer,
            flags: [._EnableAsynchronousDecompression],
            infoFlagsOut: nil
        ) { [weak self] status, _, imageBuffer, pts, _ in
            guard status == noErr, let imageBuffer else { return }
            self?.onDecodedFrame?(imageBuffer, pts)
        }
    }

    public func invalidate() {
        if let session {
            VTDecompressionSessionInvalidate(session)
        }
        session = nil
        formatDescription = nil
        sps = nil
        pps = nil
    }

    /// Splits an Annex-B elementary stream into raw NAL units (start codes removed).
    static func splitAnnexB(_ data: Data) -> [Data] {
        var nals: [Data] = []
        let bytes = [UInt8](data)
        var i = 0
        var nalStart = -1
        while i + 2 < bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 1 {
                if nalStart >= 0 {
                    var end = i
                    if end > nalStart, bytes[end - 1] == 0 { end -= 1 } // 4-byte start code
                    nals.append(Data(bytes[nalStart..<end]))
                }
                nalStart = i + 3
                i += 3
            } else {
                i += 1
            }
        }
        if nalStart >= 0, nalStart < bytes.count {
            nals.append(Data(bytes[nalStart...]))
        }
        return nals
    }
}
