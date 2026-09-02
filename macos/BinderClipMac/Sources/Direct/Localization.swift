import Foundation

enum L10n {
    private static let bundle: Bundle = {
        #if SWIFT_PACKAGE
        if Bundle.main.path(forResource: "en", ofType: "lproj") != nil {
            return Bundle.main
        }
        return Bundle.module
        #else
        return Bundle.main
        #endif
    }()

    static func tr(_ key: String, _ args: CVarArg...) -> String {
        let format = bundle.localizedString(forKey: key, value: key, table: nil)
        if args.isEmpty {
            return format
        }
        return String(format: format, locale: Locale.current, arguments: args)
    }
}
