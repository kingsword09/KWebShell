import AppKit
import ApplicationServices
import Foundation

private let applicationName = "KWebShellMigrationFixture"
private let notificationTitle = "KWebShell notification fixture"
private let actionTitle = "Open fixture"
private let authorize = CommandLine.arguments.contains("--authorize")
private let notificationCenterBundleIds: Set<String> = [
    "com.apple.notificationcenterui", "com.apple.controlcenter",
    "com.apple.UserNotificationCenter",
]

private func value(_ element: AXUIElement, _ attribute: String) -> String {
    var raw: CFTypeRef?
    guard AXUIElementCopyAttributeValue(element, attribute as CFString, &raw) == .success else { return "" }
    return raw as? String ?? ""
}

private func elements(_ element: AXUIElement, _ attribute: String) -> [AXUIElement] {
    var raw: CFTypeRef?
    guard AXUIElementCopyAttributeValue(element, attribute as CFString, &raw) == .success else { return [] }
    return raw as? [AXUIElement] ?? []
}

private func actions(_ element: AXUIElement) -> [String] {
    var raw: CFArray?
    guard AXUIElementCopyActionNames(element, &raw) == .success else { return [] }
    return raw as? [String] ?? []
}

private func labels(_ element: AXUIElement) -> [String] {
    [kAXTitleAttribute, kAXDescriptionAttribute, kAXValueAttribute].map { value(element, $0 as String) }
}

private func contains(_ element: AXUIElement, _ text: String, depth: Int = 0) -> Bool {
    guard depth < 16 else { return false }
    return labels(element).contains(where: { $0.contains(text) }) ||
        elements(element, kAXChildrenAttribute as String).contains { contains($0, text, depth: depth + 1) }
}

private func find(_ element: AXUIElement, depth: Int = 0,
                  matching predicate: (AXUIElement) -> Bool) -> AXUIElement? {
    guard depth < 16 else { return nil }
    if predicate(element) { return element }
    for child in elements(element, kAXChildrenAttribute as String) {
        if let result = find(child, depth: depth + 1, matching: predicate) { return result }
    }
    return nil
}

private func press(_ element: AXUIElement, action: String = kAXPressAction as String) -> Bool {
    AXUIElementPerformAction(element, action as CFString) == .success
}

private func openNotificationCenter() -> Bool {
    // The clock's AX identifier is independent of display size, locale and
    // menu-bar item spacing. Never guess a screen coordinate.
    for app in NSWorkspace.shared.runningApplications where app.bundleIdentifier == "com.apple.controlcenter" {
        let root = AXUIElementCreateApplication(app.processIdentifier)
        if let clock = find(root, matching: {
            value($0, kAXIdentifierAttribute as String) == "com.apple.menuextra.clock"
        }) { return press(clock) }
    }
    return false
}

private func invokeDeclaredAction(_ root: AXUIElement) -> Bool {
    if let button = find(root, matching: {
        labels($0).contains(actionTitle) && actions($0).contains(kAXPressAction as String)
    }), press(button) { return true }
    if let card = find(root, matching: { element in
        actions(element).contains { $0.contains("Name:" + actionTitle) }
    }), let action = actions(card).first(where: { $0.contains("Name:" + actionTitle) }) {
        return press(card, action: action)
    }
    return false
}

private func activateCard(_ root: AXUIElement, depth: Int = 0) -> Bool {
    guard depth < 16, contains(root, notificationTitle) else { return false }
    // Prefer the smallest matching subtree so buttons in another card cannot
    // satisfy the test when the title and action are sibling AX elements.
    for child in elements(root, kAXChildrenAttribute as String) {
        if activateCard(child, depth: depth + 1) { return true }
    }
    return invokeDeclaredAction(root)
}

private func authorizeFixture(_ root: AXUIElement) -> Bool {
    guard contains(root, applicationName) else { return false }
    let allowLabels = ["Allow", "允许"]
    let isAllowButton: (AXUIElement) -> Bool = {
        labels($0).contains(where: { allowLabels.contains($0) }) &&
            actions($0).contains(kAXPressAction as String)
    }
    if let dialog = find(root, matching: {
        contains($0, applicationName) &&
            elements($0, kAXChildrenAttribute as String).contains(where: isAllowButton)
    }), let button = elements(dialog, kAXChildrenAttribute as String).first(where: isAllowButton),
       press(button) { return true }
    // Notification Center represents the authorization alert as a card with
    // named AX actions on recent macOS versions, not as child buttons.
    let allowActions = allowLabels.map { "Name:" + $0 }
    if let card = find(root, matching: { element in
        contains(element, applicationName) &&
            actions(element).contains { allowActions.contains($0.components(separatedBy: "\n")[0]) }
    }), let action = actions(card).first(where: {
        allowActions.contains($0.components(separatedBy: "\n")[0])
    }) { return press(card, action: action) }
    return false
}

guard AXIsProcessTrusted() else {
    fputs("Notification fixture: Accessibility permission unavailable.\n", stderr)
    exit(2)
}
let deadline = Date().addingTimeInterval(authorize ? 9 : 25)
var panelOpened = false
var processCount = 0
var windowCount = 0
var matchedWindows = 0
while Date() < deadline {
    let apps = NSWorkspace.shared.runningApplications.filter {
        notificationCenterBundleIds.contains($0.bundleIdentifier ?? "") ||
            (authorize && $0.localizedName == applicationName)
    }
    processCount = apps.count
    windowCount = 0
    matchedWindows = 0
    for app in apps {
        let root = AXUIElementCreateApplication(app.processIdentifier)
        let windows = elements(root, kAXWindowsAttribute as String)
        windowCount += windows.count
        for window in windows {
            if authorize {
                if contains(window, applicationName) { matchedWindows += 1 }
                if authorizeFixture(window) {
                    print("Notification fixture: system authorization accepted")
                    exit(0)
                }
            } else if contains(window, notificationTitle) {
                matchedWindows += 1
                if activateCard(window) {
                    print("Notification fixture: declared OS action invoked")
                    exit(0)
                }
            }
        }
    }
    if !panelOpened { panelOpened = openNotificationCenter() }
    Thread.sleep(forTimeInterval: 0.2)
}
if authorize {
    fputs("Notification fixture: system authorization prompt unavailable; panelOpened=\(panelOpened) processes=\(processCount) windows=\(windowCount) matchingWindows=\(matchedWindows).\n", stderr)
    exit(3)
}
fputs("Notification fixture: action unavailable; panelOpened=\(panelOpened) processes=\(processCount) windows=\(windowCount) matchingWindows=\(matchedWindows).\n", stderr)
exit(4)
