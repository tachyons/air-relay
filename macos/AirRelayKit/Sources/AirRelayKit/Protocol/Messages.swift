import Foundation

public struct Hello: Codable, Sendable {
    public var protocolVersion: Int
    public var deviceName: String
    public var platform: String
    public var appVersion: String

    public init(deviceName: String, appVersion: String) {
        self.protocolVersion = 1
        self.deviceName = deviceName
        self.platform = "macos"
        self.appVersion = appVersion
    }
}

public struct NotificationPayload: Codable, Sendable, Identifiable {
    public var key: String
    public var packageName: String
    public var appName: String
    public var title: String
    public var text: String
    public var postedAt: Int64
    public var canReply: Bool
    public var actions: [String]?
    public var iconPng: String?

    public var id: String { key }
}

public struct NotificationDismiss: Codable, Sendable {
    public var key: String

    public init(key: String) {
        self.key = key
    }
}

public struct NotificationReply: Codable, Sendable {
    public var key: String
    public var text: String

    public init(key: String, text: String) {
        self.key = key
        self.text = text
    }
}

public struct NotificationAction: Codable, Sendable {
    public var key: String
    public var action: String

    public init(key: String, action: String) {
        self.key = key
        self.action = action
    }
}

public struct ClipboardText: Codable, Sendable {
    public var text: String
    public var ts: Int64

    public init(text: String, ts: Int64) {
        self.text = text
        self.ts = ts
    }
}

public struct CallState: Codable, Sendable {
    public var callId: String
    public var state: String
    public var displayName: String?
    public var number: String?
}

public struct CallAction: Codable, Sendable {
    public var callId: String
    public var action: String

    public init(callId: String, action: String) {
        self.callId = callId
        self.action = action
    }
}

public struct CameraStart: Codable, Sendable {
    public var width: Int
    public var height: Int
    public var fps: Int
    public var lens: String

    public init(width: Int = 1280, height: Int = 720, fps: Int = 30, lens: String = "back") {
        self.width = width
        self.height = height
        self.fps = fps
        self.lens = lens
    }
}

public struct DeviceStatus: Codable, Sendable {
    public var battery: Int
    public var charging: Bool
    public var wifiSsid: String?
}

public struct FileMeta: Codable, Sendable {
    public var transferId: String
    public var name: String
    public var size: Int64
    public var mime: String

    public init(transferId: String, name: String, size: Int64, mime: String) {
        self.transferId = transferId
        self.name = name
        self.size = size
        self.mime = mime
    }
}
