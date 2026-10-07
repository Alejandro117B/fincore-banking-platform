# FinCore

Proyecto profesional de portafolio de backend bancario y fintech.
La segunda etapa agrega PostgreSQL, JPA/Hibernate y migraciones Flyway.
No contiene endpoints, entidades de dominio ni lógica bancaria.

## Tecnologías

- Java 21 y Spring Boot 4.1.1
- Spring Web MVC y Spring Boot Test
- Spring Data JPA y Hibernate
- PostgreSQL 18 con Docker Compose y driver JDBC
- Flyway con módulo PostgreSQL
- Maven Wrapper 3.3.4 con Maven 3.9.11, empaquetado JAR

Coordenadas: `io.github.alejandro117b:fincore:0.0.1-SNAPSHOT`.
Paquete raíz: `io.github.alejandro117b.fincore`.

## Inicio local

Requisitos: JDK 21, Docker y Docker Compose con motor disponible.
La primera ejecución descarga Maven, dependencias e imagen Docker.
No se necesita instalar Maven globalmente.

Desde la raíz del repositorio en PowerShell:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# Editar .env y reemplazar POSTGRES_PASSWORD antes de continuar.
docker compose config --quiet
docker compose up -d --wait postgres
docker compose ps
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd spring-boot:run
```

Si `.env` ya existe, conservarlo. Compose lee `.env` y Spring lo importa
explícitamente como propiedades. Usar entradas simples `CLAVE=valor`, sin
comillas ni `export`. Las variables del proceso tienen prioridad sobre `.env`.
El archivo `.env` está ignorado por Git; solo se versiona `.env.example`.

| Variable | Predeterminado | Propósito |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | Host usado por FinCore |
| `POSTGRES_PORT` | `5432` | Puerto publicado y usado por FinCore |
| `POSTGRES_DB` | `fincore` | Base de datos inicial |
| `POSTGRES_USER` | `fincore` | Usuario local |
| `POSTGRES_PASSWORD` | Obligatoria | Contraseña local |

FinCore se ejecuta en el host; PostgreSQL publica su puerto solo en `127.0.0.1`.
El volumen `postgres_data` conserva los datos entre reinicios. Las variables de
inicialización solo crean credenciales y base cuando el volumen está vacío;
cambiarlas después no modifica los datos existentes.

## Migraciones y JPA

Flyway administra el esquema desde `src/main/resources/db/migration` y registra
las migraciones en `flyway_schema_history`. V1 crea `infrastructure_probe`, tabla
técnica con UUID y marcador, sin entidad de dominio ni datos iniciales.
No modificar migraciones ya aplicadas: los cambios futuros llevan otra versión.

Hibernate usa `ddl-auto: validate` y `open-in-view: false`. Todavía no hay entidades
mapeadas: la validación de Hibernate no verifica las columnas de la tabla técnica.
Las pruebas las comprueban con JDBC. La validación y persistencia de entidades JPA
se incorporarán con el primer modelo real.

## Verificación

Las cuatro pruebas requieren PostgreSQL levantado con Compose:

- Carga de contexto.
- EntityManagerFactory, Hibernate y EntityManager inicializados, modo `validate`
  y consulta de la versión de PostgreSQL mediante EntityManager.
- V1 exitosa, sin migraciones pendientes, y columnas esperadas de la tabla técnica.
- Inserción y lectura JDBC en la tabla técnica, con rollback al terminar.

Para consultar el historial en PowerShell sin mostrar la contraseña:

```powershell
'SELECT installed_rank, version, script, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;' | docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Al reiniciar FinCore, Flyway debe indicar que el esquema está actualizado; V1
conserva una sola entrada y el mismo `installed_on`. Detener FinCore con Ctrl+C.
El JAR ejecutable queda en `target/fincore-0.0.1-SNAPSHOT.jar`.

```powershell
# Detener PostgreSQL conservando el volumen.
docker compose stop postgres
# Eliminar contenedor y red conservando los datos.
docker compose down
```

En Linux/macOS usar `./mvnw` en lugar de `.\mvnw.cmd`.
Testcontainers y CI/CD quedan para una etapa posterior. No se incorporan usuarios,
cuentas, transferencias, autenticación, seguridad, JWT ni lógica financiera.
