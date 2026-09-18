/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;

class ViewStateTest {

    /** A labelled field, the shape of a TextField or a Select. */
    @Tag("test-field")
    private static class Field extends AbstractField<Field, Object> {
        Field(String label, Object value) {
            super(null);
            if (label != null) {
                getElement().setProperty("label", label);
            }
            setModelValue(value, false);
        }

        @Override
        protected void setPresentationValue(Object value) {
            // Nothing to present: the tests read the model value.
        }
    }

    /** A plain container, the shape of a view or a layout. */
    @Tag("test-container")
    private static class Container extends Component {
        Container(Component... children) {
            for (Component child : children) {
                getElement().appendChild(child.getElement());
            }
        }
    }

    /**
     * The real UI everything in a test is attached to, so that its components
     * have the state-node ids by which a touched field is recognized.
     */
    private final UI attached = new UI();

    /**
     * A UI showing the given route target. Mocked because putting a real route
     * target on a real UI needs a session, a service and a router; the scoping
     * reads nothing else off the UI, and the tree it walks is the real one
     * {@link #attach} built.
     */
    private static UI uiShowing(Component view) {
        UI ui = Mockito.mock(UI.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(ui.getInternals().getActiveRouterTargetsChain())
                .thenReturn(
                        view == null ? List.of() : List.<HasElement> of(view));
        return ui;
    }

    /** Attaches a tree, giving its components their node ids. */
    private <T extends Component> T attach(T root) {
        attached.add(root);
        return root;
    }

    private static List<String> captions(List<ComponentState> state) {
        return state.stream().map(ComponentState::caption).toList();
    }

    /** A record of the given fields having been worked by the user. */
    private TouchedFields touched(Component... fields) {
        TouchedFields touched = TouchedFields.of(attached);
        for (Component field : fields) {
            touched.add(field);
        }
        return touched;
    }

    @Test
    void readsTheValuesOfTheViewInTheOrderTheyAppear() {
        Field order = new Field("Order number", "AC-10482");
        Field reason = new Field("Reason", "Defective");
        Component button = new Container();
        Component view = attach(new Container(order, reason, button));

        List<ComponentState> state = ViewState.of(uiShowing(view), button,
                touched(order, reason));

        Assertions.assertEquals(List.of("Order number", "Reason"),
                captions(state));
        Assertions.assertEquals("Defective", state.get(1).value());
    }

    @Test
    void aFieldTheUserNeverTouchedIsAlreadyWhereTheReplayStarts() {
        // The reader opens the view and finds the order number already in it,
        // so an instruction to set it says nothing and crowds out the one
        // line that matters.
        Field order = new Field("Order number", "AC-10482");
        Field reason = new Field("Reason", "Defective");
        Component button = new Container();
        Component view = attach(new Container(order, reason, button));

        List<ComponentState> state = ViewState.of(uiShowing(view), button,
                touched(reason));

        Assertions.assertEquals(List.of("Reason"), captions(state));
    }

    @Test
    void theShellAroundTheViewIsNotPartOfTheFinding() {
        // The app switcher and the navigation are on screen throughout and
        // have nothing to do with the failure; reporting them would put the
        // same two lines in every finding the application ever produces.
        Field appSwitcher = new Field("Application", "Observability");
        Field reason = new Field("Reason", "Defective");
        Component button = new Container();
        Component view = new Container(reason, button);
        attach(new Container(appSwitcher, view));

        List<ComponentState> state = ViewState.of(uiShowing(view), button,
                touched(appSwitcher, reason));

        Assertions.assertEquals(List.of("Reason"), captions(state),
                "only the view's own values belong to the finding");
    }

    @Test
    void aComponentTheRouteTargetDoesNotHoldFallsBackToItsOwnScreen() {
        // A dialog the UI owns directly: its fields are the state of what the
        // user was looking at, and the view behind it is not.
        Field reason = new Field("Reason", "Defective");
        Component viewButton = new Container();
        Component view = attach(new Container(reason, viewButton));

        Field amount = new Field("Refund amount", "42.00");
        Component dialogButton = new Container();
        attach(new Container(amount, dialogButton));

        List<ComponentState> state = ViewState.of(uiShowing(view), dialogButton,
                touched(reason, amount));

        Assertions.assertEquals(List.of("Refund amount"), captions(state));
    }

    @Test
    void aValueTheReaderCannotFindOnScreenIsNotReported() {
        // No caption, so no way to say which of the view's fields it is.
        Field anonymous = new Field(null, "true");
        Component button = new Container();
        Component view = attach(new Container(anonymous, button));

        Assertions.assertEquals(List.of(),
                ViewState.of(uiShowing(view), button, touched(anonymous)));
    }

    @Test
    void aValueWithNoReadableTextIsNotReported() {
        // A bean without a toString: 'com.example.Order@6f2b958e' is not
        // something a reader can put into a field.
        Field bean = new Field("Customer", new Object());
        Component button = new Container();
        Component view = attach(new Container(bean, button));

        Assertions.assertEquals(List.of(),
                ViewState.of(uiShowing(view), button, touched(bean)));
    }

    @Test
    void aFieldTheUserEmptiedIsReportedAsHoldingNothing() {
        Field empty = new Field("Order number", null);
        Component button = new Container();
        Component view = attach(new Container(empty, button));

        List<ComponentState> state = ViewState.of(uiShowing(view), button,
                touched(empty));

        Assertions.assertEquals(1, state.size());
        Assertions.assertNull(state.get(0).value(),
                "a field the user cleared is state, and often the whole bug");
    }

    @Test
    void aFormLongerThanTheCapReportsItsFirstFields() {
        Component[] fields = new Component[ViewState.MAX_VALUES + 5];
        for (int i = 0; i < fields.length; i++) {
            fields[i] = new Field("Field " + i, "value " + i);
        }
        Component view = attach(new Container(fields));

        List<ComponentState> state = ViewState.of(uiShowing(view), view,
                touched(fields));

        Assertions.assertEquals(ViewState.MAX_VALUES, state.size());
        Assertions.assertEquals("Field 0", state.get(0).caption());
    }

    @Test
    void withNothingTrackedNothingIsClaimedToHaveBeenSet() {
        Field reason = new Field("Reason", "Defective");
        Component button = new Container();
        Component view = attach(new Container(reason, button));

        Assertions.assertEquals(List.of(),
                ViewState.of(uiShowing(view), button, null),
                "without a record of what the user touched, every field would "
                        + "look set");
    }

    @Test
    void noViewAndNoComponentIsNoState() {
        Assertions.assertEquals(List.of(),
                ViewState.of(uiShowing(null), null, touched()));
        Assertions.assertEquals(List.of(), ViewState.of(null, null, touched()));
    }
}
