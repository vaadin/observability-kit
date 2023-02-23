package com.example.application.data.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "address")
public class SampleAddress extends AbstractEntity {

    private String street;
    @Size(max = 5, min = 5)
    private String postalCode;
    private String city;
    private String state;
    private String country;
    @OneToOne(mappedBy = "address")
    private SamplePerson person;

    public String getStreet() {
        return street;
    }

    public void setStreet(String street) {
        this.street = street;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public SamplePerson getPerson() {
        return person;
    }

    public void setPerson(SamplePerson person) {
        this.person = person;
    }
}
