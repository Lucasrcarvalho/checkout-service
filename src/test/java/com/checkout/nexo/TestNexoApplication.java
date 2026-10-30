package com.checkout.nexo;

import org.springframework.boot.SpringApplication;

public class TestNexoApplication {

	public static void main(String[] args) {
		SpringApplication.from(NexoApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
