package com.example.AviaryService.entity;

import jakarta.persistence.*;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonManagedReference;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long Id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<ServiceTimeline> serviceTimeline;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<FlightLog> flightLogs;

    @Column
    private Double hobbsHours;

    @Column
    private Double tachHours;

    @Column
    private String makeModel;

    @Column
    private String tailNumber;

    @Column
    private String ownerName;

    @Column
    private String makeModelSN;

    @Column
    private java.time.Instant hobbsUpdatedAt;

    @Column
    private java.time.Instant tachUpdatedAt;

    @Column
    private String hobbsUpdatedSource;

    @Column
    private String tachUpdatedSource;

    public User(){

    }

    public User(String username, String password){
        this.username = username;
        this.password = password;
        this.hobbsHours = 0.0;
        this.tachHours = 0.0;
        this.makeModel = ""; 
        this.tailNumber = "";
        this.ownerName = "";
        this.makeModelSN = "";

    }

    public long getId() {
        return Id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public List<ServiceTimeline> getServiceTimeline() {
        return serviceTimeline;
    }
    
    public Double getHobbsHours() {
        return hobbsHours !=null ? hobbsHours : 0.0;
    }

    public void setHobbsHours(Double hobbsHours) {
        this.hobbsHours = (hobbsHours != null) ? hobbsHours : 0.0;
    }

    public Double getTachHours() {
        return tachHours !=null ? tachHours : 0.0;
    }

    public void setTachHours(Double tachHours) {
        this.tachHours = (tachHours != null) ? tachHours : 0.0;
    }

    public void setId(long id) {
        Id = id;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setServiceTimeline(List<ServiceTimeline> serviceTimeline) {
        this.serviceTimeline = serviceTimeline;
    }

    public String getMakeModel() { 
        return makeModel; 
    }
    public void setMakeModel(String makeModel) { 
        this.makeModel = makeModel; 
    }

    public String getTailNumber() { 
        return tailNumber; 
    }

    public void setTailNumber(String tailNumber) { 
        this.tailNumber = tailNumber; 
    }

    public String getOwnerName() { 
        return ownerName; 
    }
    
    public void setOwnerName(String ownerName) { 
        this.ownerName = ownerName; 
    }

    public String getMakeModelSN() { 
        return makeModelSN; 
    }
    
    public void setMakeModelSN(String makeModelSN) { 
        this.makeModelSN = makeModelSN; 
    }

    public List<FlightLog> getFlightLogs() {
        return flightLogs;
    }
    
    public void setFlightLogs(List<FlightLog> flightLogs) {
        this.flightLogs = flightLogs;
    }

    public java.time.Instant getHobbsUpdatedAt() { return hobbsUpdatedAt; }
    public void setHobbsUpdatedAt(java.time.Instant hobbsUpdatedAt) { this.hobbsUpdatedAt = hobbsUpdatedAt; }

    public java.time.Instant getTachUpdatedAt() { return tachUpdatedAt; }
    public void setTachUpdatedAt(java.time.Instant tachUpdatedAt) { this.tachUpdatedAt = tachUpdatedAt; }

    public String getHobbsUpdatedSource() { return hobbsUpdatedSource; }
    public void setHobbsUpdatedSource(String hobbsUpdatedSource) { this.hobbsUpdatedSource = hobbsUpdatedSource; }

    public String getTachUpdatedSource() { return tachUpdatedSource; }
    public void setTachUpdatedSource(String tachUpdatedSource) { this.tachUpdatedSource = tachUpdatedSource; }
}