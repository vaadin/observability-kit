package com.example.application.hilla.data.service;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.application.hilla.data.entity.SamplePerson;

public interface SamplePersonRepository
        extends JpaRepository<SamplePerson, UUID> {

}
