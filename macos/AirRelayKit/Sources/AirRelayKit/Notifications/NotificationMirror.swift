import Foundation
import UserNotifications
import os

/// Mirrors Android notifications into macOS Notification Center with an
/// inline reply action, and surfaces incoming calls with answer/decline.
@MainActor
public final class NotificationMirror: NSObject, UNUserNotificationCenterDelegate {
    private let log = Logger(subsystem: "dev.airrelay", category: "NotificationMirror")

    public var onReply: ((_ key: String, _ text: String) -> Void)?
    public var onDismiss: ((_ key: String) -> Void)?
    public var onAction: ((_ key: String, _ action: String) -> Void)?
    public var onCallAction: ((_ callId: String, _ action: String) -> Void)?

    private static let replyCategory = "AIRRELAY_MESSAGE"
    private static let callCategory = "AIRRELAY_CALL"
    private static let actionPrefix = "airrelay-action:"
    private var baseCategories: Set<UNNotificationCategory> = []
    private var dynamicCategories: [String: UNNotificationCategory] = [:]

    public func activate() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self

        let reply = UNTextInputNotificationAction(
            identifier: "reply",
            title: "Reply",
            options: [],
            textInputButtonTitle: "Send",
            textInputPlaceholder: "Message"
        )
        let messageCategory = UNNotificationCategory(
            identifier: Self.replyCategory,
            actions: [reply],
            intentIdentifiers: [],
            options: []
        )
        let answer = UNNotificationAction(identifier: "answer", title: "Answer", options: [])
        let decline = UNNotificationAction(
            identifier: "decline", title: "Decline", options: [.destructive]
        )
        let callCategory = UNNotificationCategory(
            identifier: Self.callCategory,
            actions: [answer, decline],
            intentIdentifiers: [],
            options: []
        )
        baseCategories = [messageCategory, callCategory]
        center.setNotificationCategories(baseCategories)
        center.requestAuthorization(options: [.alert, .sound]) { [log] granted, error in
            if let error { log.error("Notification auth error: \(error)") }
            log.info("Notification auth granted: \(granted)")
        }
    }

    public func show(_ payload: NotificationPayload) {
        let content = UNMutableNotificationContent()
        content.title = payload.title
        content.subtitle = payload.appName
        content.body = payload.text
        content.sound = .default
        content.userInfo = ["key": payload.key]
        let actions = payload.actions ?? []
        if !actions.isEmpty {
            content.categoryIdentifier = dynamicCategory(canReply: payload.canReply, actions: actions)
        } else if payload.canReply {
            content.categoryIdentifier = Self.replyCategory
        }
        let request = UNNotificationRequest(
            identifier: payload.key,
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    /// Builds (and caches) a category exposing the Android notification's
    /// action buttons, plus inline reply when supported. Categories are keyed
    /// by their action set so identical notifications share one category.
    private func dynamicCategory(canReply: Bool, actions: [String]) -> String {
        let identifier = "AIRRELAY_DYN_" + (canReply ? "R_" : "") + actions.joined(separator: "|")
        if dynamicCategories[identifier] != nil { return identifier }

        var unActions: [UNNotificationAction] = []
        if canReply {
            unActions.append(
                UNTextInputNotificationAction(
                    identifier: "reply",
                    title: "Reply",
                    options: [],
                    textInputButtonTitle: "Send",
                    textInputPlaceholder: "Message"
                )
            )
        }
        for title in actions where !(canReply && title.lowercased() == "reply") {
            unActions.append(
                UNNotificationAction(
                    identifier: Self.actionPrefix + title,
                    title: title,
                    options: []
                )
            )
        }
        let category = UNNotificationCategory(
            identifier: identifier,
            actions: unActions,
            intentIdentifiers: [],
            options: []
        )
        if dynamicCategories.count >= 32 { dynamicCategories.removeAll() }
        dynamicCategories[identifier] = category
        UNUserNotificationCenter.current()
            .setNotificationCategories(baseCategories.union(dynamicCategories.values))
        return identifier
    }

    public func showCall(_ call: CallState) {
        guard call.state == "ringing" else {
            UNUserNotificationCenter.current()
                .removeDeliveredNotifications(withIdentifiers: ["call-\(call.callId)"])
            return
        }
        let content = UNMutableNotificationContent()
        content.title = "Incoming call"
        content.body = call.displayName ?? call.number ?? "Unknown caller"
        content.sound = .defaultCritical
        content.categoryIdentifier = Self.callCategory
        content.userInfo = ["callId": call.callId]
        if let attachment = photoAttachment(call) {
            content.attachments = [attachment]
        }
        let request = UNNotificationRequest(
            identifier: "call-\(call.callId)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    /// Writes the caller's contact photo to a temp file so it can be shown
    /// in the call notification (UNNotificationAttachment requires a URL).
    private func photoAttachment(_ call: CallState) -> UNNotificationAttachment? {
        guard let base64 = call.photoPng, let data = Data(base64Encoded: base64) else { return nil }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("airrelay-caller-\(call.callId).png")
        do {
            try data.write(to: url)
            return try UNNotificationAttachment(identifier: "callerPhoto", url: url)
        } catch {
            log.warning("Caller photo attachment failed: \(error)")
            return nil
        }
    }

    public func dismiss(key: String) {
        UNUserNotificationCenter.current()
            .removeDeliveredNotifications(withIdentifiers: [key])
    }

    public func showTransferComplete(name: String, inbound: Bool) {
        let content = UNMutableNotificationContent()
        content.title = inbound ? "File received" : "File sent"
        content.body = inbound ? "\(name) saved to Downloads/AirRelay" : name
        let request = UNNotificationRequest(
            identifier: "transfer-\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    public func showTransferFailed(name: String) {
        let content = UNMutableNotificationContent()
        content.title = "Transfer interrupted"
        content.body = "\(name) didn't finish — send it again"
        let request = UNNotificationRequest(
            identifier: "transfer-fail-\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    public func showPairingRequest(sasCode: String) {
        let content = UNMutableNotificationContent()
        content.title = "Pairing request"
        content.body = "A phone wants to pair (code \(sasCode)). Open Air Relay in the menu bar to confirm."
        content.sound = .default
        let request = UNNotificationRequest(
            identifier: "pairing",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    public func dismissPairingRequest() {
        UNUserNotificationCenter.current()
            .removeDeliveredNotifications(withIdentifiers: ["pairing"])
    }

    public nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        let callId = userInfo["callId"] as? String
        let key = userInfo["key"] as? String
        let actionID = response.actionIdentifier
        let replyText = (response as? UNTextInputNotificationResponse)?.userText
        Task { @MainActor in
            if let callId {
                switch actionID {
                case "answer": onCallAction?(callId, "answer")
                case "decline": onCallAction?(callId, "reject")
                default: break
                }
            } else if let key {
                switch actionID {
                case "reply":
                    if let replyText { onReply?(key, replyText) }
                case UNNotificationDismissActionIdentifier:
                    onDismiss?(key)
                default:
                    if actionID.hasPrefix(Self.actionPrefix) {
                        onAction?(key, String(actionID.dropFirst(Self.actionPrefix.count)))
                    }
                }
            }
        }
        completionHandler()
    }

    public nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler:
            @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }
}
