package com.example.AviaryService.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.AviaryService.entity.ImportProposal;
import com.example.AviaryService.entity.User;

// Every lookup is by owner as well as id -- another owner's proposal id just
// isn't found.
public interface ImportProposalRepository extends JpaRepository<ImportProposal, Long> {
    Optional<ImportProposal> findByIdAndUser(Long id, User user);
    List<ImportProposal> findByUserAndStatusIn(User user, Collection<String> statuses);
}
