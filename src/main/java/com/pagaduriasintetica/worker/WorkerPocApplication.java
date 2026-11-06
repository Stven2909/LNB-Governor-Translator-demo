package com.pagaduriasintetica.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Entry point del Cloud Run de la Pagaduría Digital: modular monolith de LNB con el Worker,
// Gobernador, Traductor y JDBC en un solo proceso (sin HTTP interno entre módulos).
@SpringBootApplication
public class WorkerPocApplication {

	public static void main(String[] args) {
		SpringApplication.run(WorkerPocApplication.class, args);
	}

}
