package com.example.application.views.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.example.application.views.MainLayout;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.util.unit.DataSize;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;

@Route(value = "memory", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class MemoryView extends VerticalLayout {

    private final TextField freeMemory;
    private final TextField maxMemory;
    private final TextField totalMemory;

    private final List<Object> objects = new ArrayList<>();

    private transient ScheduledExecutorService scheduler;
    private transient ScheduledFuture<?> scheduledFuture;

    public MemoryView() {
        freeMemory = new TextField("Free memory");
        freeMemory.setReadOnly(true);

        maxMemory = new TextField("Max memory");
        maxMemory.setReadOnly(true);

        totalMemory = new TextField("Total memory");
        totalMemory.setReadOnly(true);

        Button allocateObjects = new Button("Allocate Objects", ev -> Stream
                .generate(Object::new).limit(10000000).forEach(objects::add));
        Button clearAllocatedObjects = new Button("Clear Objects", ev -> {
            objects.clear();
            System.gc();
        });
        setDefaultHorizontalComponentAlignment(Alignment.CENTER);

        add(new H1("Memory Summary"), freeMemory, totalMemory, maxMemory,
                new HorizontalLayout(allocateObjects, clearAllocatedObjects));
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        scheduler = Executors.newScheduledThreadPool(1);
        UI ui = attachEvent.getUI();
        scheduledFuture = scheduler.scheduleWithFixedDelay(
                () -> ui.access(this::updateStats), 500, 1000,
                TimeUnit.MILLISECONDS);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private void updateStats() {
        freeMemory.setValue(formatMemory(Runtime.getRuntime().freeMemory()));
        totalMemory.setValue(formatMemory(Runtime.getRuntime().totalMemory()));
        maxMemory.setValue(formatMemory(Runtime.getRuntime().maxMemory()));
    }

    private static String formatMemory(long memoryInBytes) {
        return String.format("%d KB",
                DataSize.ofBytes(memoryInBytes).toKilobytes());
    }
}
