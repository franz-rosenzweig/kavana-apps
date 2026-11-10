import ApplicationServices
import Foundation

/// One element of a window, as far as finding its page goes. The real one is an accessibility element
/// (AXPage below); the tests make a tree of their own.
public protocol PageNode {
    var role: String? { get }
    var parent: Self? { get }
    var children: [Self] { get }
    /// The address a web area has loaded (AXURL).
    var address: String? { get }
}

/// The page in front of a browser that has no AppleScript (Zen, and Firefox it is built on), found in the
/// window's accessibility tree. Each page is a web area that knows its own address, so nothing typed in
/// the address bar counts, and the bar's shortened "example.com" doesn't matter. Firefox's engine leaves
/// the tabs that aren't showing out of the tree, so the web area in the front window is the tab in front.
public enum PageFinder {
    public static let webArea = "AXWebArea"
    /// Roles whose insides never hold a page: menus, the tabs in the sidebar, buttons, text.
    static let leaves: Set<String> = ["AXMenu", "AXMenuBar", "AXMenuItem", "AXRadioButton", "AXButton", "AXStaticText",
                                      "AXImage", "AXTextField", "AXCheckBox", "AXPopUpButton", "AXHeading", "AXLink"]

    /// The web area that has the keyboard (in a split view, the side being used), else the first one in the window.
    /// The outermost one: a video from another site framed inside a page is part of that page.
    public static func address<N: PageNode>(focused: N?, window: N?, maxNodes: Int = 4000) -> String? {
        var e = focused, page: N?
        for _ in 0..<120 {
            guard let n = e else { break }
            if n.role == webArea { page = n }
            e = n.parent
        }
        if let page { return page.address }
        guard let window else { return nil }
        var queue = [window], i = 0
        while i < queue.count, i < maxNodes {
            let n = queue[i]; i += 1
            let role = n.role ?? ""
            if role == webArea { return n.address }
            if !leaves.contains(role) { queue.append(contentsOf: n.children) }
        }
        return nil
    }
}

/// An element of another app's window, read with the Accessibility API. Each read is a question to that app,
/// answered within half a second or not at all.
public struct AXPage: PageNode {
    public let element: AXUIElement
    public init(_ e: AXUIElement) {
        element = e
        AXUIElementSetMessagingTimeout(e, 0.5)   // a browser that hangs can't hold a look up for long
    }

    func value(_ attribute: String) -> AnyObject? {
        var v: AnyObject?
        return AXUIElementCopyAttributeValue(element, attribute as CFString, &v) == .success ? v : nil
    }
    func element(_ attribute: String) -> AXPage? {
        guard let v = value(attribute), CFGetTypeID(v) == AXUIElementGetTypeID() else { return nil }
        return AXPage(v as! AXUIElement)
    }
    public var role: String? { value(kAXRoleAttribute) as? String }
    public var parent: AXPage? { element(kAXParentAttribute) }
    public var children: [AXPage] { (value(kAXChildrenAttribute) as? [AXUIElement] ?? []).map(AXPage.init) }
    public var address: String? {
        let v = value("AXURL")
        return (v as? URL)?.absoluteString ?? v as? String
    }

    /// The address of the page in front in this app's front window. The first question wakes Firefox's
    /// accessibility up, so the very first look after it starts can come back empty.
    public static func address(pid: pid_t) -> String? {
        let a = AXPage(AXUIElementCreateApplication(pid))
        return PageFinder.address(focused: a.element(kAXFocusedUIElementAttribute), window: a.element(kAXFocusedWindowAttribute) ?? a.element(kAXMainWindowAttribute))
    }

    /// May Chataltul read other apps this way? macOS keeps it under Privacy & Security → Accessibility.
    /// `ask`: show macOS's own prompt, which sends the person there.
    public static func allowed(ask: Bool = false) -> Bool {
        AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: ask] as CFDictionary)
    }
}
