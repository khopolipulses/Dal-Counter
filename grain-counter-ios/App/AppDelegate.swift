import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        HistoryStore.clearShareFiles() // pictures handed to other apps last time are not kept
        return true
    }

    func application(_ application: UIApplication,
                     configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Default", sessionRole: session.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

/// Swipe-back works on every pushed screen (also where the bar is hidden) and never on the first screen, where an
/// edge swipe with nothing to go back to would leave the navigation stuck.
final class SwipeBack: NSObject, UIGestureRecognizerDelegate {
    weak var nav: UINavigationController?

    func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        guard let nav = nav else { return false }
        return nav.viewControllers.count > 1 && nav.transitionCoordinator == nil
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private let swipeBack = SwipeBack()

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
        swipeBack.nav = nav
        nav.interactivePopGestureRecognizer?.delegate = swipeBack
        w.rootViewController = nav
        w.tintColor = Theme.accent
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-uitest-dark") { w.overrideUserInterfaceStyle = .dark }
        #endif
        w.makeKeyAndVisible()
        window = w
    }
}
