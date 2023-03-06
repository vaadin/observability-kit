package com.example.application.data.generator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.example.application.data.entity.SampleAddress;
import com.example.application.data.entity.SamplePerson;
import com.example.application.data.service.SampleAddressRepository;
import com.example.application.data.service.SamplePersonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;

import com.vaadin.exampledata.DataType;
import com.vaadin.exampledata.ExampleDataGenerator;
import com.vaadin.flow.spring.annotation.SpringComponent;

@SpringComponent
public class DataGenerator {

    @Bean
    public CommandLineRunner loadData(
            SamplePersonRepository samplePersonRepository,
            SampleAddressRepository sampleAddressRepository) {
        return args -> {
            Logger logger = LoggerFactory.getLogger(getClass());
            if (samplePersonRepository.count() != 0L) {
                logger.info("Using existing database");
                return;
            }
            int seed = 123;

            logger.info("Generating demo data");

            logger.info("... generating 3000 Sample Person entities...");
            ExampleDataGenerator<SamplePerson> samplePersonRepositoryGenerator = new ExampleDataGenerator<>(
                    SamplePerson.class, LocalDateTime.of(2022, 9, 5, 0, 0, 0));
            samplePersonRepositoryGenerator.setData(SamplePerson::setFirstName,
                    DataType.FIRST_NAME);
            samplePersonRepositoryGenerator.setData(SamplePerson::setLastName,
                    DataType.LAST_NAME);
            samplePersonRepositoryGenerator.setData(SamplePerson::setEmail,
                    DataType.EMAIL);
            samplePersonRepositoryGenerator.setData(SamplePerson::setPhone,
                    DataType.PHONE_NUMBER);
            samplePersonRepositoryGenerator.setData(
                    SamplePerson::setDateOfBirth, DataType.DATE_OF_BIRTH);
            samplePersonRepositoryGenerator.setData(SamplePerson::setOccupation,
                    DataType.OCCUPATION);
            samplePersonRepositoryGenerator.setData(SamplePerson::setImportant,
                    DataType.BOOLEAN_10_90);
            List<SamplePerson> persons = samplePersonRepositoryGenerator
                    .create(3000, seed);
            List<SampleAddress> addresses = new ArrayList<>(persons.size());

            // Assign address to each person
            persons.forEach(person -> {
                SampleAddress address = new SampleAddress();
                address.setStreet("Fake Street 123");
                address.setCity("Fake City");
                address.setCountry("Country");
                address.setPostalCode("99999");
                addresses.add(address);
                person.setAddress(address);
            });

            sampleAddressRepository.saveAll(addresses);
            samplePersonRepository.saveAll(persons);

            logger.info("Generated demo data");
        };
    }

}
