package com.example.application.data.service;

import java.util.UUID;

import com.example.application.data.entity.SampleAddress;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SampleAddressRepository
        extends JpaRepository<SampleAddress, UUID> {

}
