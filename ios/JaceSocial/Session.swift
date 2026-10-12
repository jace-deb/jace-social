import Foundation
import Security

/// The signed-in account: its token (in the Keychain) and the API that uses it. Also the account
/// switcher: the other accounts signed in on this phone (and bots you switched to).
final class Session: ObservableObject {
    @Published private(set) var token: String?
    @Published private(set) var accounts: [SavedAccount] = []
    private(set) lazy var api = Api { [weak self] in self?.token }

    init() {
        token = Keychain.read()
        accounts = Keychain.read(account: "accounts").flatMap { try? JSONDecoder().decode([SavedAccount].self, from: Data($0.utf8)) } ?? []
        // test builds: -jaceToken <t> signs in with that token (CI screenshots with native-test/mock_api.py).
        // Not through the Keychain: CI's unsigned simulator builds can't use it.
        #if DEBUG
        if let t = UserDefaults.standard.string(forKey: "jaceToken") { token = t }
        #endif
    }

    func signedIn(_ s: SignedIn) { use(s.token) }

    private func use(_ t: String?) {
        Keychain.write(t)
        token = t
    }

    func signOut() async {
        let t = token
        await api.signOut()
        save(accounts.filter { $0.token != t })
        use(nil)
    }

    /// The server said the token is no good any more (401).
    func expired() {
        let t = token
        save(accounts.filter { $0.token != t })
        use(nil)
    }

    // --- account switcher
    /// Remember the account that's signed in now (after loading /me).
    func remember(_ me: Me) {
        guard let t = token else { return }
        remember(SavedAccount(uuid: me.uuid, name: me.shownName, avatar_url: me.avatar_url, token: t, is_bot: me.is_bot))
    }
    func remember(_ a: SavedAccount) { save(Array(([a] + accounts.filter { $0.uuid != a.uuid }).prefix(10))) }
    func switchTo(_ uuid: String) { if let a = accounts.first(where: { $0.uuid == uuid }) { use(a.token) } }
    func forget(_ uuid: String) { save(accounts.filter { $0.uuid != uuid }) }
    /// Sign in to another account: this one stays in the list.
    func addAnother() { use(nil) }

    private func save(_ list: [SavedAccount]) {
        accounts = list
        Keychain.write((try? JSONEncoder().encode(list)).flatMap { String(data: $0, encoding: .utf8) }, account: "accounts")
    }
}

enum Keychain {
    private static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "dev.jacedeb.jacesocial", kSecAttrAccount as String: account]
    }

    static func read(account: String = "session") -> String? {
        var q = query(account)
        q[kSecReturnData as String] = true
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func write(_ value: String?, account: String = "session") {
        SecItemDelete(query(account) as CFDictionary)
        guard let v = value else { return }
        var q = query(account)
        q[kSecValueData as String] = Data(v.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(q as CFDictionary, nil)
    }
}
