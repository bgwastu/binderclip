import AppKit

final class LogWindowController: NSWindowController, NSToolbarDelegate, NSSearchFieldDelegate {
    private let textView = NSTextView()
    private var searchField: NSSearchField?
    private var filterSegments: NSSegmentedControl?
    private var autoScrollButton: NSButton?
    private var countLabel: NSTextField?
    private var observer: NSObjectProtocol?

    private var searchQuery = ""
    private var filterSegmentIndex = 0
    private var autoScrollEnabled = true

    private let toolbarID = NSToolbar.Identifier("LogWindowToolbar")
    private let clearID = NSToolbarItem.Identifier("logClear")
    private let copyID = NSToolbarItem.Identifier("logCopy")
    private let autoScrollID = NSToolbarItem.Identifier("logAutoScroll")

    init() {
        // Native scroll view with text view following system Light/Dark appearance
        let scroll = NSScrollView()
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.heightAnchor.constraint(greaterThanOrEqualToConstant: 300).isActive = true

        textView.isEditable = false
        textView.isSelectable = true
        textView.isRichText = true
        textView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        textView.textColor = .textColor
        textView.backgroundColor = .textBackgroundColor
        textView.drawsBackground = true
        textView.textContainerInset = NSSize(width: 12, height: 12)
        scroll.documentView = textView

        // Top search field (NSSearchField) for real-time live log searching
        let search = NSSearchField()
        search.placeholderString = L10n.tr("log_search_placeholder")
        search.target = nil
        search.translatesAutoresizingMaskIntoConstraints = false
        search.widthAnchor.constraint(greaterThanOrEqualToConstant: 220).isActive = true
        searchField = search

        // Segmented control filter: All | Errors | Warnings | Info
        let segments = NSSegmentedControl(labels: [
            L10n.tr("log_filter_all"),
            L10n.tr("log_filter_errors"),
            L10n.tr("log_filter_warnings"),
            L10n.tr("log_filter_info")
        ], trackingMode: .selectOne, target: nil, action: nil)
        segments.selectedSegment = 0
        filterSegments = segments

        let topSpacer = NSView()
        topSpacer.setContentHuggingPriority(.defaultLow, for: .horizontal)

        let topBar = NSStackView(views: [search, topSpacer, segments])
        topBar.orientation = .horizontal
        topBar.alignment = .centerY
        topBar.distribution = .fill
        topBar.spacing = 10

        // Bottom status & action controls
        let count = NSTextField(labelWithString: "")
        count.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        count.textColor = .secondaryLabelColor
        countLabel = count

        let autoScroll = NSButton(title: L10n.tr("log_auto_scroll"), target: nil, action: nil)
        autoScroll.setButtonType(.pushOnPushOff)
        autoScroll.bezelStyle = .rounded
        autoScroll.state = .on
        autoScrollButton = autoScroll

        let copy = NSButton(title: L10n.tr("log_copy"), target: nil, action: nil)
        copy.bezelStyle = .rounded

        let clear = NSButton(title: L10n.tr("log_clear"), target: nil, action: nil)
        clear.bezelStyle = .rounded

        let close = NSButton(title: L10n.tr("log_close"), target: nil, action: nil)
        close.bezelStyle = .rounded

        let bottomSpacer = NSView()
        bottomSpacer.setContentHuggingPriority(.defaultLow, for: .horizontal)

        let bottomBar = NSStackView(views: [count, bottomSpacer, autoScroll, copy, clear, close])
        bottomBar.orientation = .horizontal
        bottomBar.alignment = .centerY
        bottomBar.spacing = 8

        let content = NSStackView(views: [topBar, scroll, bottomBar])
        content.orientation = .vertical
        content.spacing = 10
        content.edgeInsets = NSEdgeInsets(top: 12, left: 14, bottom: 12, right: 14)
        content.setHuggingPriority(.defaultLow, for: .vertical)

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 680, height: 460),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = L10n.tr("log_window_title")
        window.isReleasedWhenClosed = false
        window.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]
        window.contentView = content
        window.minSize = NSSize(width: 520, height: 320)

        super.init(window: window)

        // Set up toolbar buttons: Clear, Copy, Auto-scroll toggle
        let toolbar = NSToolbar(identifier: toolbarID)
        toolbar.allowsUserCustomization = false
        toolbar.autosavesConfiguration = false
        toolbar.displayMode = .iconAndLabel
        toolbar.delegate = self
        window.toolbar = toolbar
        if #available(macOS 11.0, *) {
            window.toolbarStyle = .unified
        }

        search.target = self
        search.action = #selector(searchFieldChanged(_:))
        search.delegate = self

        segments.target = self
        segments.action = #selector(filterSegmentChanged(_:))

        autoScroll.target = self
        autoScroll.action = #selector(toggleAutoScroll)

        clear.target = self
        clear.action = #selector(clearLogs)

        copy.target = self
        copy.action = #selector(copyLogs)

        close.target = self
        close.action = #selector(closeWindow)

        observer = NotificationCenter.default.addObserver(
            forName: DiagnosticLog.changed,
            object: DiagnosticLog.shared,
            queue: nil
        ) { [weak self] _ in
            DispatchQueue.main.async { self?.refresh() }
        }
        refresh()
    }

    required init?(coder: NSCoder) { nil }
    deinit { observer.map(NotificationCenter.default.removeObserver) }

    override func showWindow(_ sender: Any?) {
        refresh()
        super.showWindow(sender)
        window?.center()
        window?.makeKeyAndOrderFront(sender)
        window?.orderFrontRegardless()
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func searchFieldChanged(_ sender: NSSearchField) {
        searchQuery = sender.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        refresh()
    }

    func controlTextDidChange(_ obj: Notification) {
        guard let field = obj.object as? NSSearchField, field == searchField else { return }
        searchQuery = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        refresh()
    }

    @objc private func filterSegmentChanged(_ sender: NSSegmentedControl) {
        filterSegmentIndex = sender.selectedSegment
        refresh()
    }

    @objc private func toggleAutoScroll() {
        autoScrollEnabled.toggle()
        autoScrollButton?.state = autoScrollEnabled ? .on : .off
        if autoScrollEnabled {
            textView.scrollToEndOfDocument(nil)
        }
    }

    @objc private func clearLogs() {
        DiagnosticLog.shared.clear()
    }

    @objc private func copyLogs() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(textView.string, forType: .string)
    }

    @objc private func closeWindow() {
        window?.performClose(nil)
    }

    // MARK: - NSToolbarDelegate

    func toolbarAllowedItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        [.flexibleSpace, autoScrollID, copyID, clearID]
    }

    func toolbarDefaultItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        [.flexibleSpace, autoScrollID, copyID, clearID]
    }

    func toolbar(_ toolbar: NSToolbar, itemForItemIdentifier itemIdentifier: NSToolbarItem.Identifier, willBeInsertedIntoToolbar flag: Bool) -> NSToolbarItem? {
        if itemIdentifier == clearID {
            let item = NSToolbarItem(itemIdentifier: clearID)
            item.label = L10n.tr("log_clear")
            item.image = NSImage(systemSymbolName: "trash", accessibilityDescription: item.label)
            item.target = self
            item.action = #selector(clearLogs)
            return item
        } else if itemIdentifier == copyID {
            let item = NSToolbarItem(itemIdentifier: copyID)
            item.label = L10n.tr("log_copy")
            item.image = NSImage(systemSymbolName: "doc.on.doc", accessibilityDescription: item.label)
            item.target = self
            item.action = #selector(copyLogs)
            return item
        } else if itemIdentifier == autoScrollID {
            let item = NSToolbarItem(itemIdentifier: autoScrollID)
            item.label = L10n.tr("log_auto_scroll")
            item.image = NSImage(systemSymbolName: "arrow.down.to.line", accessibilityDescription: item.label)
            item.target = self
            item.action = #selector(toggleAutoScroll)
            return item
        }
        return nil
    }

    // MARK: - Filtering & Refresh

    private func filteredEvents() -> [DiagnosticEvent] {
        var events = DiagnosticLog.shared.snapshot()

        switch filterSegmentIndex {
        case 1:
            events = events.filter { $0.level == .error }
        case 2:
            events = events.filter { $0.level == .warning }
        case 3:
            events = events.filter { $0.level == .info }
        default:
            break
        }

        if !searchQuery.isEmpty {
            let query = searchQuery.lowercased()
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm:ss"
            events = events.filter { event in
                event.message.localizedCaseInsensitiveContains(query) ||
                event.level.rawValue.localizedCaseInsensitiveContains(query) ||
                formatter.string(from: event.date).contains(query)
            }
        }

        return events
    }

    private func refresh() {
        let events = filteredEvents()
        countLabel?.stringValue = L10n.tr("log_events_count", events.count)

        if events.isEmpty {
            let emptyAttrs: [NSAttributedString.Key: Any] = [
                .font: NSFont.monospacedSystemFont(ofSize: 12, weight: .regular),
                .foregroundColor: NSColor.secondaryLabelColor
            ]
            let emptyText = searchQuery.isEmpty && filterSegmentIndex == 0
                ? L10n.tr("log_no_events")
                : "$ No matching events."
            textView.textStorage?.setAttributedString(NSAttributedString(string: emptyText, attributes: emptyAttrs))
            return
        }

        let timeAttrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.monospacedSystemFont(ofSize: 11, weight: .medium),
            .foregroundColor: NSColor.secondaryLabelColor
        ]
        let messageAttrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.monospacedSystemFont(ofSize: 12, weight: .regular),
            .foregroundColor: NSColor.textColor
        ]

        let result = NSMutableAttributedString()
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"

        for (index, event) in events.enumerated() {
            if index > 0 {
                result.append(NSAttributedString(string: "\n"))
            }

            result.append(NSAttributedString(string: formatter.string(from: event.date) + " ", attributes: timeAttrs))

            let levelColor: NSColor
            switch event.level {
            case .error:
                levelColor = .systemRed
            case .warning:
                levelColor = .systemOrange
            case .info:
                levelColor = .systemBlue
            }
            let levelAttrs: [NSAttributedString.Key: Any] = [
                .font: NSFont.monospacedSystemFont(ofSize: 11, weight: .bold),
                .foregroundColor: levelColor
            ]
            result.append(NSAttributedString(string: "[\(event.level.rawValue.uppercased())] ", attributes: levelAttrs))
            result.append(NSAttributedString(string: event.message, attributes: messageAttrs))
        }

        textView.textStorage?.setAttributedString(result)
        if autoScrollEnabled {
            textView.scrollToEndOfDocument(nil)
        }
    }
}
