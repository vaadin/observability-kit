package com.example.application.views.creditcardform;

import com.example.application.views.MainLayout;
import jakarta.annotation.security.RolesAllowed;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.customfield.CustomField;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.PreserveOnRefresh;
import com.vaadin.flow.router.Route;

@PageTitle("Credit Card Form")
@Route(value = "credit-card-form", layout = MainLayout.class)
@PreserveOnRefresh
@RolesAllowed("USER")
public class CreditCardFormView extends Div {

    private final TextField cardNumber = new TextField("Credit card number");
    private final TextField cardholderName = new TextField("Cardholder name");
    private final Select<Integer> month = new Select<>();
    private final Select<Integer> year = new Select<>();
    private final ExpirationDateField expiration = new ExpirationDateField(
            "Expiration date", month, year);
    private final PasswordField csc = new PasswordField("CSC");

    private final Button cancel = new Button("Cancel");
    private final Button submit = new Button("Submit");

    public CreditCardFormView() {
        addClassName("credit-card-form-view");

        add(createTitle());
        add(createFormLayout());
        add(createButtonLayout());

        cancel.addClickListener(e -> UI.getCurrent().getPage().reload());
        submit.addClickListener(e -> UI.getCurrent().getPage().reload());
    }

    private Component createTitle() {
        return new H3("Credit Card");
    }

    private Component createFormLayout() {
        FormLayout formLayout = new FormLayout();
        formLayout.add(cardNumber, cardholderName, expiration, csc);
        return formLayout;
    }

    private Component createButtonLayout() {
        HorizontalLayout buttonLayout = new HorizontalLayout();
        buttonLayout.addClassName("button-layout");
        cardNumber.setPlaceholder("1234 5678 9123 4567");
        cardNumber.setPattern("[\\d ]*");
        cardNumber.setAllowedCharPattern("[\\d ]");
        cardNumber.setRequired(true);
        cardNumber.setErrorMessage("Please enter a valid credit card number");
        month.setPlaceholder("Month");
        month.setItems(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        year.setPlaceholder("Year");
        year.setItems(20, 21, 22, 23, 24, 25);
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        buttonLayout.add(submit);
        buttonLayout.add(cancel);
        return buttonLayout;
    }

    private static class ExpirationDateField extends CustomField<String> {
        public ExpirationDateField(String label, Select<Integer> month,
                Select<Integer> year) {
            setLabel(label);
            HorizontalLayout layout = new HorizontalLayout(month, year);
            layout.setFlexGrow(1.0, month, year);
            month.setWidth("100px");
            year.setWidth("100px");
            add(layout);
        }

        @Override
        protected String generateModelValue() {
            // Unused as month and year fields part are of the outer class
            return "";
        }

        @Override
        protected void setPresentationValue(String newPresentationValue) {
            // Unused as month and year fields part are of the outer class
        }

    }

}
