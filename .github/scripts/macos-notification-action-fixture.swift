import AppKit
import ApplicationServices
import Foundation

private let applicationName = "KWebShellMigrationFixture"
private let notificationTitle = "KWebShell notification fixture"
private let notificationCenterBundleId = "com.apple.notificationcenterui"

private func value(_ element: AXUIElement, _ attribute: String) -> String {
    var rawValue: CFTypeRef?
    guard AXUIElementCopyAttributeValue(element, attribute as CFString, &rawValue) == .success,
          let rawValue else { return "" }
    return rawValue as? String ?? String(describing: rawValue)
}

private func notificationCard(in element: AXUIElement, depth: Int = 0) -> AXUIElement? {
    guard depth <= 16 else { return nil }
    let description = value(element, kAXDescriptionAttribute as String)
    if description.contains(applicationName) && description.contains(notificationTitle) {
        return element
    }
    var rawChildren: CFTypeRef?
    guard AXUIElementCopyAttributeValue(element, kAXChildrenAttribute as CFString, &rawChildren) == .success,
          let rawChildren else { return nil }
    for child in rawChildren as! [AXUIElement] {
        if let card = notificationCard(in: child, depth: depth + 1) { return card }
    }
    return nil
}

private func openNotificationCenter() throws {
    let display = CGDisplayBounds(CGMainDisplayID())
    let clockCenter = CGPoint(x: display.maxX - 48, y: display.minY + 12)
    guard let source = CGEventSource(stateID: .combinedSessionState),
          let down = CGEvent(mouseEventSource: source, mouseType: .leftMouseDown, mouseCursorPosition: clockCenter, mouseButton: .left),
          let up = CGEvent(mouseEventSource: source, mouseType: .leftMouseUp, mouseCursorPosition: clockCenter, mouseButton: .left) else {
        throw NSError(domain: "KWebNotificationActionFixture", code: 1)
    }
    down.post(tap: .cghidEventTap)
    up.post(tap: .cghidEventTap)
}

do {
    guard AXIsProcessTrusted() else {
        fputs("macOS notification action fixture requires Accessibility permission for the test process.\n", stderr)
        exit(2)
    }
    try openNotificationCenter()

    let deadline = Date().addingTimeInterval(25)
    while Date() < deadline {
        if let app = NSWorkspace.shared.runningApplications.first(where: {
            $0.bundleIdentifier == notificationCenterBundleId
        }) {
            let application = AXUIElementCreateApplication(app.processIdentifier)
            var rawWindows: CFTypeRef?
            if AXUIElementCopyAttributeValue(application, kAXWindowsAttribute as CFString, &rawWindows) == .success,
               let rawWindows {
                for window in rawWindows as! [AXUIElement] {
                    guard let card = notificationCard(in: window) else { continue }
                    var rawActions: CFArray?
                    guard AXUIElementCopyActionNames(card, &rawActions) == .success,
                          let rawActions else { continue }
                    let action = (rawActions as! [String]).first(where: { name in
                        let normalized = name.lowercased()
                        return normalized.contains("name:open") || normalized.contains("打开")
                    })
                    guard let action else { continue }
                    let result = AXUIElementPerformAction(card, action as CFString)
                    guard result == .success else {
                        fputs("macOS notification action activation failed (AXError \(result.rawValue)).\n", stderr)
                        exit(3)
                    }
                    print("macOS notification OS action activated")
                    exit(0)
                }
            }
        }
        Thread.sleep(forTimeInterval: 0.25)
    }
    fputs("The real macOS notification action was not exposed by Notification Center within 25 seconds.\n", stderr)
    exit(4)
} catch {
    fputs("Could not open macOS Notification Center for the action fixture (\(error)).\n", stderr)
    exit(5)
}
