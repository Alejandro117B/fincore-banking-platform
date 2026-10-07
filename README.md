# FinCore

Proyecto profesional de portafolio orientado a backend bancario y fintech.
Esta primera etapa establece únicamente la base técnica: no contiene endpoints
ni lógica bancaria.

## Tecnologías

- Java 21
- Spring Boot 4.1.1
- Spring Web MVC
- Spring Boot Test (solo para pruebas)
- Maven mediante Maven Wrapper
- Empaquetado JAR

Coordenadas: `io.github.alejandro117b:fincore:0.0.1-SNAPSHOT`.
Paquete raíz: `io.github.alejandro117b.fincore`.

## Requisitos

JDK 21 disponible en `PATH` o mediante `JAVA_HOME`. No se necesita instalar Maven.
La primera ejecución del Wrapper necesita acceso a Internet para descargar Maven
y las dependencias.

## Comandos

Desde la raíz del repositorio, en PowerShell:

```powershell
# Ejecutar la prueba de carga de contexto
.\mvnw.cmd test

# Compilar, ejecutar las pruebas y generar el JAR ejecutable
.\mvnw.cmd clean verify

# Iniciar la aplicación
.\mvnw.cmd spring-boot:run
```

En Linux/macOS, usar `./mvnw` en lugar de `.\mvnw.cmd`.
El JAR se genera en `target/fincore-0.0.1-SNAPSHOT.jar`.

## Estructura

```text
.mvn/wrapper/maven-wrapper.properties
mvnw
mvnw.cmd
pom.xml
src/main/java/io/github/alejandro117b/fincore/FinCoreApplication.java
src/main/resources/application.yml
src/test/java/io/github/alejandro117b/fincore/FinCoreApplicationTests.java
```

El Wrapper usa la distribución oficial de Maven y su variante `only-script`,
que no requiere incluir un JAR del Wrapper en el repositorio.

## Alcance de esta etapa

La configuración contiene únicamente Spring Web MVC y Spring Boot Test.
PostgreSQL, Spring Data JPA, Flyway, Spring Security, JWT, Docker Compose
y las funcionalidades bancarias quedan para etapas posteriores.
