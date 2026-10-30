package com.checkout.nexo.checkout.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.checkout.nexo.checkout.domain.Cart;

import jakarta.persistence.LockModeType;

public interface CartRepository extends JpaRepository<Cart, UUID> {

	/**
	 * Le o carrinho com lock pessimista de escrita ({@code FOR UPDATE}); so tem efeito dentro de
	 * uma transacao. Serializa checkouts concorrentes do mesmo carrinho (DUP-01).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Cart c where c.id = :id")
	Optional<Cart> findByIdForUpdate(@Param("id") UUID id);

}
