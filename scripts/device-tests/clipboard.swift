import AppKit
import Foundation
let args = CommandLine.arguments
let p = NSPasteboard(name: .init("net.wastu.binderclip.debug.mb.clipboard"))
let file = URL(fileURLWithPath: args.count > 2 ? args[2] : "/tmp/binderclip-clipboard-backup.plist")
switch args[1] {
case "system-change-count": print(NSPasteboard.general.changeCount)
case "save":
    let items = (p.pasteboardItems ?? []).map { item in Dictionary(uniqueKeysWithValues: item.types.compactMap { type in item.data(forType: type).map { (type.rawValue, $0) } }) }
    try PropertyListSerialization.data(fromPropertyList: items, format: .binary, options: 0).write(to: file, options: .atomic)
    try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
case "restore":
    let items = try PropertyListSerialization.propertyList(from: Data(contentsOf: file), options: [], format: nil) as! [[String: Data]]
    p.clearContents()
    let objects = items.map { value -> NSPasteboardItem in let item = NSPasteboardItem(); for (type, data) in value { item.setData(data, forType: NSPasteboard.PasteboardType(type)) }; return item }
    if !objects.isEmpty { p.writeObjects(objects) }
case "service-text":
    let board = NSPasteboard.withUniqueName()
    defer { board.releaseGlobally() }
    board.setString(args[2], forType: .string)
    NSUpdateDynamicServices()
    exit(NSPerformService("Send to Phones via BinderClip Debug", board) ? 0 : 1)
case "match-text": exit(p.string(forType: .string) == args[2] ? 0 : 1)
case "match-text-file": exit(p.string(forType: .string) == (try String(contentsOf: file, encoding: .utf8)) ? 0 : 1)
case "set-text-file": p.clearContents(); p.setString(try String(contentsOf: file, encoding: .utf8), forType: .string)
case "set-text": p.clearContents(); p.setString(args[2], forType: .string)
case "match-image": exit(p.data(forType: .png) == (try Data(contentsOf: file)) ? 0 : 1)
case "set-image": p.clearContents(); p.setData(try Data(contentsOf: file), forType: .png)
default: exit(2)
}
