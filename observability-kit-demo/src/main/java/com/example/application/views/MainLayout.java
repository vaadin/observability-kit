package com.example.application.views;

import com.example.application.components.appnav.AppNav;
import com.example.application.components.appnav.AppNavItem;
import com.example.application.views.addressform.AddressFormView;
import com.example.application.views.client.ClientComponentsView;
import com.example.application.views.creditcardform.CreditCardFormView;
import com.example.application.views.helloworld.HelloWorldView;
import com.example.application.views.imagelist.ImageListView;
import com.example.application.views.list.ListView;
import com.example.application.views.login.LoginView;
import com.example.application.views.masterdetail.MasterDetailView;
import com.example.application.views.masterdetail.MasterDetailViewNPlusOne;
import com.example.application.views.masterdetail.MasterDetailViewPlain;
import com.example.application.views.memory.MemoryView;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Footer;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.auth.AccessAnnotationChecker;
import com.vaadin.flow.spring.security.AuthenticationContext;

/**
 * The main view is a top-level placeholder for other views.
 */
public class MainLayout extends AppLayout {

    private H1 viewTitle;

    private final AuthenticationContext authenticationContext;
    private final AccessAnnotationChecker accessChecker;

    public MainLayout(AuthenticationContext authenticationContext,
            AccessAnnotationChecker accessChecker) {
        this.authenticationContext = authenticationContext;
        this.accessChecker = accessChecker;
        setPrimarySection(Section.DRAWER);
        addToNavbar(true, createHeaderContent());
        addToDrawer(createDrawerContent());
    }

    private Component createHeaderContent() {
        DrawerToggle toggle = new DrawerToggle();
        toggle.addClassNames("view-toggle");
        toggle.addThemeVariants(ButtonVariant.LUMO_CONTRAST);
        toggle.getElement().setAttribute("aria-label", "Menu toggle");

        viewTitle = new H1();
        viewTitle.addClassNames("view-title");

        Header header = new Header(toggle, viewTitle);
        header.addClassNames("view-header");
        return header;
    }

    private Component createDrawerContent() {
        H2 appName = new H2("My App");
        appName.addClassNames("app-name");

        com.vaadin.flow.component.html.Section section = new com.vaadin.flow.component.html.Section(
                appName, createNavigation(), createFooter());
        section.addClassNames("drawer-section");
        return section;
    }

    private AppNav createNavigation() {
        // AppNav is not yet an official component.
        // For documentation, visit https://github.com/vaadin/vcf-nav#readme
        AppNav nav = new AppNav();
        nav.addClassNames("app-nav");

        if (accessChecker.hasAccess(HelloWorldView.class)) {
            nav.addItem(new AppNavItem("Hello World", HelloWorldView.class,
                    "la la-glass-cheers"));
        }
        if (accessChecker.hasAccess(AddressFormView.class)) {
            nav.addItem(new AppNavItem("Address Form", AddressFormView.class,
                    "lab la-mandalorian"));
        }
        if (accessChecker.hasAccess(MasterDetailViewNPlusOne.class)) {
            nav.addItem(new AppNavItem("Master-Detail-N-Plus-One",
                    MasterDetailViewNPlusOne.class, "la la-crow"));
        }
        if (accessChecker.hasAccess(MasterDetailViewPlain.class)) {
            nav.addItem(new AppNavItem("Master-Detail-Plain",
                    MasterDetailViewPlain.class, "la la-crow"));
        }
        if (accessChecker.hasAccess(MasterDetailView.class)) {
            nav.addItem(new AppNavItem("Master-Detail", MasterDetailView.class,
                    "la la-crow"));
        }
        if (accessChecker.hasAccess(CreditCardFormView.class)) {
            nav.addItem(new AppNavItem("Credit Card Form",
                    CreditCardFormView.class, "la la-frog"));
        }
        if (accessChecker.hasAccess(ImageListView.class)) {
            nav.addItem(new AppNavItem("Image List", ImageListView.class,
                    "la la-images"));
        }
        if (accessChecker.hasAccess(ListView.class)) {
            nav.addItem(new AppNavItem("List", ListView.class, "la la-th"));
        }
        if (accessChecker.hasAccess(MemoryView.class)) {
            nav.addItem(
                    new AppNavItem("Memory", MemoryView.class, "la la-memory"));
        }
        if (accessChecker.hasAccess(ClientComponentsView.class)) {
            nav.addItem(new AppNavItem("Client component",
                    ClientComponentsView.class, "la la-puzzle-piece"));
        }

        return nav;
    }

    private Footer createFooter() {
        Footer layout = new Footer();
        layout.addClassNames("app-nav-footer");
        authenticationContext.getPrincipalName().ifPresentOrElse(
                username -> layout.add(
                        new Button("Logout",
                                ev -> authenticationContext.logout()),
                        new Span(username)),
                () -> layout.add(new RouterLink("Login", LoginView.class)));
        return layout;
    }

    @Override
    protected void afterNavigation() {
        super.afterNavigation();
        viewTitle.setText(getCurrentPageTitle());
    }

    private String getCurrentPageTitle() {
        PageTitle title = getContent().getClass()
                .getAnnotation(PageTitle.class);
        return title == null ? "" : title.value();
    }
}
