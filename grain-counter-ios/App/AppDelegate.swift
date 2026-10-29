import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Default", sessionRole: session.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let ws = scene as? UIWindowScene else { return }
        let w = UIWindow(windowScene: ws)
        let nav = UINavigationController(rootViewController: HomeViewController())
        nav.navigationBar.prefersLargeTitles = true
        nav.navigationBar.tintColor = Theme.accent
        let clear = UINavigationBarAppearance()
        clear.configureWithTransparentBackground()          // large titles float over the glass background
        let frosted = UINavigationBarAppearance()
        frosted.configureWithDefaultBackground()            // frosted once content scrolls under the bar
        nav.navigationBar.scrollEdgeAppearance = clear
        nav.navigationBar.standardAppearance = frosted
        nav.navigationBar.compactAppearance = frosted
        w.rootViewController = nav
        w.tintColor = Theme.accent
        w.makeKeyAndVisible()
        window = w
    }
}
