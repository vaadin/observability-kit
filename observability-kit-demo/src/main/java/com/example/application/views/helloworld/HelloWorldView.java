package com.example.application.views.helloworld;

import java.util.Random;

import com.example.application.views.MainLayout;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@PageTitle("Hello World")
@Route(value = "hello", layout = MainLayout.class)
@RouteAlias(value = "", layout = MainLayout.class)
@AnonymousAllowed
public class HelloWorldView extends HorizontalLayout {

    private final TextField name;
    private final Button sayHello;
    private final Button longTask;

    public HelloWorldView() {
        name = new TextField("Your name");
        sayHello = new Button("Say hello");
        sayHello.addClickListener(e -> {
            if (new Random().nextInt(15) % 5 == 0) {
                throw new IllegalStateException("Exception on the backend!");
            }
            Notification.show("Hello " + name.getValue());
        });
        sayHello.addClickShortcut(Key.ENTER);

        longTask = new Button("Long running task");
        longTask.addClickListener(e -> {
            startLongTask();
            Notification.show("Job completed for " + name.getValue());
        });

        setMargin(true);
        setVerticalComponentAlignment(Alignment.END, name, sayHello, longTask);

        add(name, sayHello, longTask);
    }

    @WithSpan
    private void startLongTask() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}
