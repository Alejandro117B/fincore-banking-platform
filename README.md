# FinCore

Proyecto profesional de portafolio de backend bancario y fintech.
La tercera etapa introduce Customer y Account sobre PostgreSQL, JPA/Hibernate
y migraciones Flyway. Incluye reglas básicas del dominio y persistencia real;
todavía no contiene endpoints ni operaciones financieras.

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
V2 crea `customers`; V3 crea `accounts`, su FK al titular y un índice sobre
`customer_id`. No hay datos de ejemplo. No modificar migraciones ya aplicadas:
los cambios futuros llevan otra versión.

Hibernate usa `ddl-auto: validate` y `open-in-view: false`. Valida las tablas y
columnas mapeadas de Customer y Account al inicializarse. Los CHECK, índices y
reglas referenciales se definen mediante Flyway y se prueban directamente en SQL.
La tabla técnica de V1 continúa sin entidad JPA y se comprueba mediante JDBC.

## Dominio y estructura

```text
io.github.alejandro117b.fincore
├── FinCoreApplication
├── customer
│   ├── Customer
│   └── CustomerRepository
└── account
    ├── Account
    ├── AccountRepository
    ├── AccountStatus
    └── AccountType
```

Los únicos conceptos de dominio son Customer y Account. Los enums y repositorios
se ubican junto a su funcionalidad, sin capas adicionales ni Lombok.

- Customer representa una persona física. Tiene UUID, nombres obligatorios,
  email de contacto opcional, timestamps y versión. El email no es único ni una
  identidad de autenticación. `updateContactDetails(...)` valida todos los datos
  antes de modificar el objeto. El formato de email se valida de forma básica;
  no se verifica existencia o entrega del correo.
- Account tiene UUID, titular obligatorio, tipo CHECKING/SAVINGS, estado,
  `String currencyCode`, timestamps y versión. La moneda se normaliza con
  `Locale.ROOT` y se valida mediante `Currency.getInstance`. SQL garantiza el
  formato de tres letras mayúsculas; no contiene un catálogo de monedas.
- La relación Account → Customer es `ManyToOne` unidireccional y lazy, sin cascadas.
  `Customer` no tiene colección de cuentas. El repositorio permite consultar por
  `customerId`. El titular, tipo y moneda no tienen métodos de modificación y sus
  columnas se excluyen de los UPDATE de Hibernate.
- Una cuenta nace ACTIVE. **Esto no representa onboarding, aprobación ni KYC**:
  esos procesos no se modelan todavía. `block(at)` requiere ACTIVE, `unblock(at)`
  requiere BLOCKED y `close(at)` admite ambos estados. CLOSED es terminal; incluso
  repetir el cierre produce `IllegalStateException`. `closedAt` se establece al
  cerrar y coincide con `updatedAt` en ese momento.
- Las fábricas y cambios reciben un `Instant` explícito, obtenido por quien invoca
  el dominio, por ejemplo mediante `clock.instant()`. No hay llamadas a
  `Instant.now()`, callbacks de timestamp ni setters generales. Los cambios no
  permiten instantes anteriores a `updatedAt`; el mismo instante sí es válido.
- UUID se genera en Java. `@Version Long` comienza en null y Hibernate administra
  su valor persistido, no nulo y no negativo. No se incrementa manualmente.
- Los timestamps usan Instant y PostgreSQL `timestamptz`. La base tiene precisión
  de microsegundos; no conserva la zona original. Las pruebas de round trip usan
  esa precisión. No confundir estos campos con un historial de auditoría.

Las restricciones SQL incluyen nombres no vacíos, NOT NULL, longitudes, versiones
no negativas, enums permitidos, coherencia temporal y `closed_at` obligatorio solo
para CLOSED. La FK usa `ON DELETE RESTRICT`: eliminar al titular con cuentas falla.
El cierre irreversible se garantiza mediante el comportamiento Java; las escrituras
SQL directas deben respetar también las reglas del dominio.

## Verificación

Las pruebas unitarias de Customer y Account no requieren Spring ni PostgreSQL:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=CustomerTests,AccountTests" test
```

La suite completa requiere PostgreSQL levantado con Compose. Incluye:

- Creación válida e inválida, moneda, transiciones y timestamps deterministas.
- Contexto con Hibernate en modo validate y las dos entidades reales registradas.
- Historial V1/V2/V3 exitoso, sin migraciones pendientes, y tabla técnica de V1.
- Persistencia, flush y lectura después de limpiar el contexto, relación lazy,
  varias cuentas por cliente, enums, moneda y timestamps.
- Restricciones SQL, FK inválida y ON DELETE RESTRICT. El rechazo de borrado en
  PostgreSQL 18 se comprueba como SQLSTATE 23001 (restrict_violation).
- Optimistic locking para Customer y Account con dos EntityManager y transacciones
  independientes: solo la primera actualización se confirma.

Las pruebas normales hacen rollback. Las de concurrencia necesitan datos
confirmados y eliminan únicamente sus fixtures identificados por UUID al terminar.

Para consultar el historial en PowerShell sin mostrar la contraseña:

```powershell
'SELECT installed_rank, version, script, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;' | docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Al reiniciar FinCore, Flyway debe indicar que el esquema está actualizado; V1/V2/V3
conservan una sola entrada y el mismo `installed_on`. Detener FinCore con Ctrl+C.
El JAR ejecutable queda en `target/fincore-0.0.1-SNAPSHOT.jar`.

```powershell
# Detener PostgreSQL conservando el volumen.
docker compose stop postgres
# Eliminar contenedor y red conservando los datos.
docker compose down
```

En Linux/macOS usar `./mvnw` en lugar de `.\mvnw.cmd`.
Testcontainers y CI/CD quedan para una etapa posterior. No se incorporan balances,
números de cuenta, CLABE, beneficiarios, transferencias, movimientos, autenticación,
seguridad, JWT, endpoints ni ledger. CHECKING y SAVINGS son clasificaciones: no
implican intereses ni sobregiros. Las condiciones financieras para cerrar cuentas
se definirán cuando exista el modelo de movimientos y saldos.
