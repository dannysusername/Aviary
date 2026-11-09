package com.example.AviaryService.repositories;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.AviaryService.entity.User;

public interface UserRepository extends JpaRepository<User, Long>{
    User findByUsername(String username);

    // Public stats (services/stats/PublicStatsService): accounts created after
    // a moment. Accounts with no createdAt (made before it was recorded) never match.
    long countByCreatedAtAfter(Instant since);

    // Public stats: the newest signup times, newest first. Returns timestamps
    // only -- never User rows -- so no account details reach the stats code.
    @Query("select u.createdAt from User u where u.createdAt is not null order by u.createdAt desc")
    List<Instant> findRecentSignupTimes(Pageable page);
}
