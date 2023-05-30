package com.example.application.data.service;

import java.util.UUID;

import com.example.application.data.entity.SamplePerson;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SamplePersonRepository
        extends JpaRepository<SamplePerson, UUID> {

}
