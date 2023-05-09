package com.example.application.hilla.data.service;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.application.hilla.data.entity.SampleAddress;

public interface SampleAddressRepository
        extends JpaRepository<SampleAddress, UUID> {

}
