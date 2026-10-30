package com.checkout.nexo.checkout.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.checkout.nexo.checkout.domain.PaymentAttempt;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

}
