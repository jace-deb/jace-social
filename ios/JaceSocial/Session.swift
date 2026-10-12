import Foundation
import Security

/// The signed-in account: its token (in the Keychain) and the API that uses it.
final class Session: ObservableObject {
    @Published private(set) var token: String?
    private(set) lazy var api = Api { [weak self] in self?.token }

    init() {
        token = Keychain.read()
    }

    func signedIn(_ s: SignedIn) {
        Keychain.write(s.token)
        token = s.token
    }

    func signOut() async {
        await api.signOut()
        expired()
    }

    /// The server said the token is no good any more (401).
    func expired() {
        Keychain.write(nil)
        token = nil
    }
}

enum Keychain {
    private static let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                               kSecAttrService as String: "dev.jacedeb.jacesocial",
                                               kSecAttrAccount as String: "session"]

    static func read() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func write(_ value: String?) {
        SecItemDelete(query as CFDictionary)
        guard let v = value else { return }
        var q = query
        q[kSecValueData as String] = Data(v.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(q as CFDictionary, nil)
    }
}
