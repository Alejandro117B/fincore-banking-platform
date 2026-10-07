# FinCore

Proyecto profesional de portafolio de backend bancario y fintech.
La cuarta etapa agrega un núcleo de ledger de partida doble sobre Customer,
Account, PostgreSQL y JPA/Hibernate. Contabiliza journals completos e inmutables;
todavía no contiene endpoints ni casos de uso de depósito, retiro o transferencia.

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
V4 crea `ledger_accounts`; V5 crea `journal_transactions`; V6 crea
`ledger_entries`, funciones, triggers e índices del ledger.

Hibernate usa `ddl-auto: validate` y `open-in-view: false`. Valida las tablas y
columnas de las cinco entidades al inicializarse. Los CHECK, índices, triggers y
reglas referenciales se definen mediante Flyway y se prueban directamente en SQL.
La tabla técnica de V1 continúa sin entidad JPA y se comprueba mediante JDBC.

## Dominio y estructura

```text
io.github.alejandro117b.fincore
├── FinCoreApplication
├── customer
│   ├── Customer
│   └── CustomerRepository
├── account
│   ├── Account
│   ├── AccountRepository
│   ├── AccountStatus
│   └── AccountType
└── ledger
    ├── LedgerAccount
    ├── JournalTransaction
    ├── LedgerEntry
    ├── LedgerAccountCategory
    ├── JournalStatus
    ├── EntrySide
    ├── LedgerPostingService
    ├── LedgerBalanceService
    ├── LedgerAccountRepository
    ├── JournalTransactionRepository
    ├── LedgerEntryRepository
    └── LedgerValidation
```

Los enums, servicios y repositorios se ubican junto a su funcionalidad,
sin capas adicionales ni Lombok.

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

## Ledger de partida doble

LedgerAccount representa exactamente una cuenta bancaria o una cuenta interna
con `systemCode`. Las cuentas de clientes son LIABILITY; las internas pueden ser
ASSET o LIABILITY. Su identidad, categoría y moneda son inmutables también en SQL,
para evitar reinterpretar el historial. No existe ningún campo `balance`.

JournalTransaction es la cabecera: UUID, moneda, DRAFT/POSTED, reference opcional
y timestamps explícitos. No tiene operationType, idempotencyKey, requestHash ni
una colección JPA de líneas. LedgerEntry relaciona la cabecera con LedgerAccount,
un número de línea, DEBIT/CREDIT y una cantidad positiva de la misma moneda.
Todas las relaciones son unidireccionales y lazy, sin cascadas ni orphanRemoval.

`LedgerPostingService.post(journal, entries, at)` valida un snapshot de las líneas:
al menos dos, al menos dos cuentas contables, ambos lados, cantidades positivas,
moneda común, pertenencia al journal, números de línea únicos e igualdad de totales.
Dentro de una transacción Spring inserta DRAFT y las líneas, hace flush, marca
POSTED y vuelve a hacer flush. La entidad no presupone conocer líneas persistidas.
Si existe una transacción externa, la confirmación solo ocurre al terminar esa
transacción. Ante un rollback se deben descartar las entidades en memoria usadas
en el intento; su estado Java no prueba que estén confirmadas.

### Cantidades y saldo

Se usa BigDecimal y NUMERIC(19,4), con cantidades positivas hasta
`999999999999999.9999`. Java exige representación exacta mediante
`RoundingMode.UNNECESSARY`: rechaza decimales significativos más allá de cuatro;
ceros adicionales pueden eliminarse sin redondear. SQL rechaza cero, negativos y
NaN. PostgreSQL puede redondear entradas SQL directas al convertir a NUMERIC(19,4):
la comprobación de precisión original se realiza en la entrada Java del servicio.
Los futuros casos de uso impondrán además la granularidad de cada moneda.

`LedgerBalanceService.getBalance(ledgerAccountId)` suma solo journals POSTED:

- LIABILITY: créditos menos débitos.
- ASSET: débitos menos créditos.

Una cuenta contable existente sin entradas devuelve `0.0000`; un UUID desconocido
se rechaza. No hay saldo disponible ni proyección materializada. El ledger puede
representar saldos negativos: partida doble no sustituye la validación de fondos.

### Guards PostgreSQL (V6)

| Función | Protección |
|---|---|
| `ledger_guard_entry_insert` | Bloquea la cabecera y solo permite insertar en DRAFT |
| `ledger_reject_entry_mutation` | Rechaza UPDATE/DELETE de líneas, incluso durante la construcción |
| `ledger_guard_journal_write` | Inserción DRAFT, única transición a POSTED, sin edición de metadatos ni borrado |
| `ledger_validate_journal` | Trigger diferido: exige POSTED y comprueba el balanceo completo antes del commit |
| `ledger_reject_account_update` | Impide alterar identidad, moneda o categoría contable |
| `ledger_reject_truncate` | Rechaza TRUNCATE de las tres tablas del ledger |

El constraint trigger `ct_journal_complete` es DEFERRABLE INITIALLY DEFERRED.
Una transacción no puede confirmar DRAFT, ni un POSTED incompleto o desbalanceado.
Las FKs compuestas garantizan la moneda común con el journal, LedgerAccount y Account.
Los triggers consultan tablas calificadas con TG_TABLE_SCHEMA, para no confiar en
el search_path del invocador. Cambios futuros requieren journals compensatorios;
no se altera el asiento original. No se implementa todavía ese caso de uso.

Estas protecciones no detienen a un administrador que pueda desactivar triggers
o alterar el esquema. Más adelante se separarán los usuarios Flyway y runtime,
con mínimos privilegios. Las credenciales locales actuales son privilegiadas.

El bloqueo de la cabecera protege su finalización e inserción de líneas; no es un
protocolo de validación de fondos. AccountStatus, saldo suficiente, cierre con
saldo cero y bloqueo ordenado de Account/LedgerAccount pertenecen a futuros casos
de uso. `@Version` de Account no protege por sí solo inserciones en el ledger.
La idempotencia se diseñará separadamente de los registros contables.

## Verificación

Las pruebas unitarias de Customer y Account no requieren Spring ni PostgreSQL:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=CustomerTests,AccountTests" test
```

La suite completa requiere PostgreSQL levantado con Compose. Incluye:

- Creación válida e inválida, moneda, transiciones y timestamps deterministas.
- Contexto con Hibernate en modo validate y las cinco entidades reales registradas.
- Historial V1–V6 exitoso, sin migraciones pendientes, y tabla técnica de V1.
- Persistencia, flush y lectura después de limpiar el contexto, relación lazy,
  varias cuentas por cliente, enums, moneda y timestamps.
- Restricciones SQL, FK inválida y ON DELETE RESTRICT. El rechazo de borrado en
  PostgreSQL 18 se comprueba como SQLSTATE 23001 (restrict_violation).
- Optimistic locking para Customer y Account con dos EntityManager y transacciones
  independientes: solo la primera actualización se confirma.

Las pruebas normales hacen rollback. Las de concurrencia necesitan datos
confirmados y eliminan únicamente sus fixtures identificados por UUID al terminar.

Las pruebas unitarias del ledger comprueban las invariantes y el orden de
contabilización. `LedgerIntegrationTests` usa un esquema generado por ejecución
en el PostgreSQL de Compose, con las mismas migraciones V1–V6. TransactionTemplate
realiza commits tanto válidos como inválidos para probar los triggers diferidos.
El esquema aislado se elimina administrativamente al terminar, sin desactivar
guards ni modificar public. Las pruebas necesitan permiso para crear ese esquema.

Se comprueban rollback completo, ataques SQL de edición/borrado/truncado, inserción
posterior a POSTED, monedas, relaciones lazy, cuentas internas y cálculo de saldos.
La transferencia conceptual de 500 MXN utiliza líneas contables de prueba:
el saldo origen baja de 1000 a 500 y el destino sube de 0 a 500, conservando 1000.
No existe un servicio de transferencias ni una operación de depósito para crear
esos fixtures.

Para consultar el historial en PowerShell sin mostrar la contraseña:

```powershell
'SELECT installed_rank, version, script, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;' | docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Al reiniciar FinCore, Flyway debe indicar que el esquema está actualizado; V1–V6
conservan una sola entrada y el mismo `installed_on`. Detener FinCore con Ctrl+C.
El JAR ejecutable queda en `target/fincore-0.0.1-SNAPSHOT.jar`.

```powershell
# Detener PostgreSQL conservando el volumen.
docker compose stop postgres
# Eliminar contenedor y red conservando los datos.
docker compose down
```

En Linux/macOS usar `./mvnw` en lugar de `.\mvnw.cmd`.
Testcontainers y CI/CD quedan para una etapa posterior. No se incorporan campos
balance, números de cuenta, CLABE, beneficiarios, reservas, depósitos, retiros,
casos de uso de transferencias, autenticación, JWT, endpoints, Kafka, Redis ni
microservicios. CHECKING y SAVINGS son clasificaciones: no implican intereses
ni sobregiros. No hay divisas, reconciliación externa ni idempotencia de solicitudes.
