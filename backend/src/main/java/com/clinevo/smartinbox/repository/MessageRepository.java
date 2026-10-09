package com.clinevo.smartinbox.repository;

import com.clinevo.smartinbox.domain.Message;
import com.clinevo.smartinbox.domain.enums.MessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {
    Optional<Message> findByExternalRef(String externalRef);
    List<Message> findByStatusOrderByReceivedAtAsc(MessageStatus status);
    List<Message> findAllByOrderByReceivedAtDesc();
    Optional<Message> findTopByExternalRefStartingWithOrderByReceivedAtDesc(String externalRefPrefix);
}
