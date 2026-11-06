package com.pagaduriasintetica.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

// Entry point del Cloud Run de la Pagaduría Digital: modular monolith de LNB con el Worker,
// Gobernador, Traductor y JDBC en un solo proceso (sin HTTP interno entre módulos).
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class WorkerPocApplication {

	public static void main(String[] args) {
		SpringApplication.run(WorkerPocApplication.class, args);
	}

}
