# FinCore API v1 — fases 6 y 7A

Fase 7A añade login JWT, ownership para lecturas de Customer/Account y creación
administrativa. Transferencias exige autenticación, pero ownership e idempotencia
por identidad quedan pendientes de 7B. V1–V7 y el ledger permanecen intactos;
V8 crea identidad/roles, sin balances mutables. Ver [security-7a.md](security-7a.md).

## Endpoints y DTOs

| Método | Ruta | Request | Response | Éxito |
|---|---|---|---|---|
| POST | /api/v1/auth/login | LoginRequest | LoginResponse | 200 |
| GET | /api/v1/auth/me | Bearer JWT | IdentityResponse | 200 |
| POST | /api/v1/customers | CreateCustomerRequest | CustomerResponse | 201 + Location |
| GET | /api/v1/customers/{id} | UUID en ruta | CustomerResponse | 200 |
| POST | /api/v1/accounts | CreateAccountRequest | AccountResponse | 201 + Location |
| GET | /api/v1/accounts/{id} | UUID en ruta | AccountResponse | 200 |
| GET | /api/v1/accounts/{id}/balance | UUID en ruta | AccountBalanceResponse | 200 |
| GET | /api/v1/accounts/{id}/transactions | UUID, limit, cursor | AccountTransactionsResponse | 200 |
| POST | /api/v1/transfers | CreateTransferRequest + Idempotency-Key | TransferResponse | 201 + Location |
| GET | /api/v1/transfers/{id} | UUID en ruta | TransferResponse | 200 |

Se aceptan/retornan JSON. Las respuestas de negocio llevan Cache-Control: no-store
y X-Request-Id. No hay listados globales, edición, eliminación, cambios de estado
por HTTP, administración contable, depósitos ni retiros.

Los contratos son records Java y usan mapping explícito:

- CreateCustomerRequest: firstName (1–100, no blanco), lastName (1–150, no blanco),
  email opcional (máximo 254, formato de contacto validado).
- CustomerResponse: id, firstName, lastName, email nullable, createdAt, updatedAt.
- CreateAccountRequest: customerId, type (CHECKING/SAVINGS), currencyCode.
- AccountResponse: id, customerId, type, status, currencyCode, createdAt, updatedAt,
  closedAt nullable.
- AccountBalanceResponse: accountId, currencyCode, postedBalance, calculatedAt.
  calculatedAt describe la ejecución de la consulta, no un snapshot histórico.
- CreateTransferRequest: sourceAccountId, destinationAccountId, amount,
  currencyCode, reference opcional (máximo 128, no blanco cuando se envía).
- TransferResponse: id, sourceAccountId, destinationAccountId, amount,
  currencyCode, reference nullable, journalTransactionId, createdAt, completedAt.
- AccountTransactionResponse: journalTransactionId, transferId nullable, postedAt,
  currencyCode, debitAmount, creditAmount, netAmount, reference nullable.
- AccountTransactionsResponse: items, hasMore, nextCursor nullable.

No se serializan entidades JPA, @Version, hashes, registros idempotentes,
proxies ni objetos internos del ledger. journalTransactionId es solo trazabilidad.

UUID usa la representación canónica con guiones; valores abreviados o base64 se
rechazan. Instant usa ISO-8601 UTC con Z. Las monedas se normalizan a mayúsculas.
Las cuentas admiten monedas reconocidas por java.util.Currency; las transferencias
admiten exclusivamente MXN (2 decimales), USD (2) y JPY (0). Una cuenta EUR puede
existir, pero no puede transferir EUR en esta versión.

## Dinero y JSON estricto

Todos los importes son **JSON strings**, tanto cantidades como saldos/totales.
Una cantidad se convierte directamente mediante BigDecimal desde el texto.
No hay conversiones intermedias mediante double/float ni redondeos silenciosos.

Válido:

```json
{
  "sourceAccountId": "00000000-0000-0000-0000-000000000001",
  "destinationAccountId": "00000000-0000-0000-0000-000000000002",
  "amount": "500.00",
  "currencyCode": "MXN",
  "reference": "Internal transfer"
}
```

Inválido: `"amount": 500.00` como token numérico. También se rechazan strings
con notación científica, comas, separadores de miles, signo +, espacios o ceros
iniciales ambiguos. El texto decimal tiene longitud máxima 40.
Las respuestas usan escala 4, por ejemplo `"500.0000"` o `"-500.0000"`.
Los netos y saldos derivados pueden ser negativos; las cantidades transferidas
deben ser estrictamente positivas.

Jackson 3 rechaza propiedades desconocidas, claves JSON duplicadas, tokens
adicionales después del body, coerciones número/boolean a String, enums numéricos
o desconocidos y UUID no canónicos. No se aceptan silenciosamente balance,
status, requestHash o estructuras contables enviadas en el body.

Bean Validation comprueba presencia, longitud y sintaxis. El servicio existente
conserva las reglas económicas: moneda soportada, positividad, rango, granularidad,
fondos, estados, locking e idempotencia. La ausencia o sintaxis incorrecta de
amount da 400; un decimal sintácticamente correcto pero económicamente inválido
(como negativo o MXN 0.001) da 422 INVALID_AMOUNT.

## Creación y consultas

POST /accounts crea Account y LedgerAccount LIABILITY en una sola transacción.
Un fallo revierte ambas. No crea journal ni fondos. Una cuenta nueva correctamente
preparada devuelve postedBalance = "0.0000".

Customer/Account no implementan idempotencia: repetir sus POST puede crear otros
recursos. Account nace ACTIVE sin representar onboarding, aprobación ni KYC.

El saldo se calcula mediante LedgerBalanceService sobre journals POSTED;
LIABILITY = credits - debits. No existe saldo disponible ni proyección materializada.
Una Account existente sin LedgerAccount devuelve 409 ACCOUNT_NOT_READY, también
al consultar historial o intentar transferir. Nunca se presenta como saldo cero.

BLOCKED/CLOSED se pueden consultar, incluyendo balance/historial.
Las transferencias ordinarias solo admiten ambas cuentas ACTIVE.

## Transferencias e idempotencia

Idempotency-Key es obligatorio únicamente en POST /api/v1/transfers:

- Exactamente un valor.
- Longitud 1–128 y patrón [A-Za-z0-9._:-]+.
- Case-sensitive. No se normaliza ni se genera automáticamente.
- Alcance global del caso de uso; el futuro alcance por identidad queda pendiente.

El controller solo valida el header, mapea el body a TransferCommand y delega.
No calcula hashes, reserva keys ni abre una transacción alrededor del servicio.

Original y replay exitoso devuelven **201, body y Location originales**. Cada
request tiene su propio X-Request-Id. Una reference diferente con el mismo
contenido económico devuelve la reference original: reference no forma parte
del hash. Un cambio económico con la misma key devuelve 409 IDEMPOTENCY_CONFLICT.

Los rechazos esperados se confirman como REJECTED antes de comunicar el error HTTP.
Un retry reproduce el código y mensaje de negocio, aunque luego se agreguen
fondos. timestamp/requestId del ApiError corresponden al request HTTP actual.
Un rechazo no crea una Transfer consultable mediante GET.

Fallos técnicos dentro de la transacción hacen rollback completo y no consumen
la key. Un fallo de red/serialización después del commit no revierte lo confirmado:
ante resultado HTTP incierto, repetir la **misma key y contenido económico**.

## Historial y cursor

GET /accounts/{id}/transactions?limit=20&cursor=...

Una fila resume la participación de esa cuenta en un journal POSTED. Las múltiples
líneas para esa cuenta se agregan. Para LIABILITY, netAmount = credits - debits.
transferId es nullable porque no todos los journals representan una Transfer.

Se usa JdbcTemplate con proyección agregada y parámetros enlazados, sin colecciones
JPA, OFFSET ni consulta COUNT total. No hay endpoints públicos de escritura
para LedgerAccount, JournalTransaction o LedgerEntry.

- Default limit = 20; mínimo 1; máximo 100.
- ORDER BY postedAt DESC, journalTransactionId DESC.
- Lectura de limit + 1 para saber si existe otra página.
- Cursor opaco, base64url sin padding, contrato v1 y accountId obligatorio.
- Guarda la clave superior del recorrido y la última clave devuelta.
- El cliente debe reenviar nextCursor sin interpretarlo/modificarlo.
- Cursor inválido, de otra versión o cuenta: 400 INVALID_CURSOR.
- Paginación inválida: 400 INVALID_LIMIT.
- Al finalizar: hasMore = false, nextCursor = null.

La codificación del cursor no es una firma ni autorización. No garantiza un
snapshot congelado: nuevos journals con claves superiores quedan excluidos del
recorrido iniciado, pero un journal confirmado tarde con timestamp anterior puede
aparecer durante el recorrido o quedar fuera de una parte ya recorrida. Se debe
iniciar una consulta nueva para refrescar el historial.

Se validó la consulta mediante ejecución real y EXPLAIN con los índices actuales.
El volumen de fixtures no permite concluir rendimiento a escala bancaria; no se
agregan índices especulativos. Medir con datos representativos antes de proponer V8.

## Errores seguros y request ID

```json
{
  "timestamp": "2026-10-07T13:00:00Z",
  "status": 409,
  "code": "INSUFFICIENT_FUNDS",
  "message": "Insufficient funds for this transfer.",
  "path": "/api/v1/transfers",
  "requestId": "00000000-0000-0000-0000-000000000003",
  "details": []
}
```

ApiError contiene timestamp, status, code, message seguro, path como plantilla
de ruta, requestId y details. Los detalles de validación contienen field, code
y message; no contienen el valor rechazado. No se incluyen query strings,
headers, stack traces, SQL, constraint names, SQLSTATE o excepciones Hibernate.

RequestIdFilter genera un UUID en el servidor, ignora el X-Request-Id suministrado
por el cliente, devuelve X-Request-Id, lo instala en MDC y registra método,
plantilla de ruta y status. El MDC se restaura al terminar el request.
Los errores técnicos se registran internamente junto con requestId y diagnóstico.

| TransferErrorCode | HTTP | Código público |
|---|---|---|
| INVALID_REQUEST | 400 | INVALID_REQUEST |
| INVALID_AMOUNT | 422 | INVALID_AMOUNT |
| INVALID_CURRENCY | 422 | INVALID_CURRENCY |
| ACCOUNT_NOT_FOUND | 404 | ACCOUNT_NOT_FOUND |
| LEDGER_ACCOUNT_NOT_FOUND | 409 | ACCOUNT_NOT_READY |
| INSUFFICIENT_FUNDS | 409 | INSUFFICIENT_FUNDS |
| ACCOUNT_BLOCKED | 409 | ACCOUNT_BLOCKED |
| ACCOUNT_CLOSED | 409 | ACCOUNT_CLOSED |
| CURRENCY_MISMATCH | 422 | CURRENCY_MISMATCH |
| SAME_ACCOUNT | 422 | SAME_ACCOUNT |
| IDEMPOTENCY_CONFLICT | 409 | IDEMPOTENCY_CONFLICT |

El advice también controla VALIDATION_ERROR/MALFORMED_REQUEST/INVALID_UUID (400),
recursos inexistentes (404), métodos (405 conservando Allow), Accept (406) y
Content-Type (415). Customer puede devolver INVALID_CUSTOMER_DETAILS (422);
titular inexistente al crear Account devuelve CUSTOMER_NOT_FOUND (404).

Fallos inesperados y constraints SQL técnicos: 500 INTERNAL_ERROR.
Fallos transitorios identificados de base de datos/locks: 503 SERVICE_UNAVAILABLE.
No se convierte indiscriminadamente IllegalArgumentException a 4xx y no se hace
retry automático. Los errores HTTP fuera del procesamiento MVC (p. ej. un request
rechazado por el servidor/proxy antes de llegar a Spring) no comparten necesariamente
el ApiError; las opciones de fallback excluyen diagnósticos internos.

## Configuración y OpenAPI

| Variable | Default |
|---|---|
| SERVER_ADDRESS | 127.0.0.1 |
| SERVER_PORT | 8080 |
| OPENAPI_ENABLED | true |
| SWAGGER_UI_ENABLED | true |

Se pueden sobrescribir mediante variables del proceso, .env o propiedades Spring.
No hay perfiles adicionales. Para deshabilitar la documentación, poner ambas
habilitaciones en false; Swagger UI necesita el documento habilitado para funcionar.

Springdoc tiene versión explícita 3.1.1 en pom.xml; Spring Boot no administra
esta dependencia externa. Se verificó con Boot 4.1.1 mediante compilación, arranque
y requests reales a OpenAPI y Swagger UI.
[Documentación de Springdoc](https://springdoc.org/getting-started.html).

OpenAPI se obtiene en /v3/api-docs; Swagger UI en /swagger-ui.html.
El v3 del documento no es una versión de los recursos bancarios. La documentación
incluye solo /api/v1/**, los DTOs, ApiError, importes como strings, headers,
201 original/replay, Location, nullable y ejemplos. Incluye Bearer JWT y
401/403; login se declara público. Las rutas de documentación se permiten solo en dev.

Dependencias nuevas:

- org.springframework.boot:spring-boot-starter-validation
- org.springframework.boot:spring-boot-starter-webmvc-test (test)
- org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1

## Paquetes y servicios

```text
io.github.alejandro117b.fincore
├── api
│   ├── ApiConfiguration / JsonConfiguration / StrictUuidDeserializer
│   ├── ApiError / ApiValidationError / ApiException / ApiExceptionHandler
│   └── ApiInputs / RequestIdFilter / TransferHttpErrors
├── customer
│   ├── CustomerService
│   └── api: CustomerController / CreateCustomerRequest / CustomerResponse / CustomerApiMapper
├── account
│   ├── AccountService / AccountStatementQueryService
│   └── api: AccountController / CreateAccountRequest / AccountResponse
│            AccountBalanceResponse / AccountTransactionResponse / AccountTransactionsResponse
│            AccountApiMapper / AccountTransactionsCursor
└── transfer
    ├── TransferQueryService (TransferService existente conserva su frontera)
    └── api: TransferController / CreateTransferRequest / TransferResponse / TransferApiMapper
```

Los nuevos servicios mapean snapshots/DTOs dentro de las transacciones de lectura
o creación, sin dejar proxies a los consumidores ni depender de open-in-view.
No se introducen interfaces genéricas, repositorios CRUD nuevos ni capas DDD.

## Pruebas y límites restantes

ApiMappingTests cubre precisión, contratos públicos, códigos HTTP y cursor.
ApiMvcTests usa el slice MVC con la configuración JSON real y prueba DTOs,
coerciones, headers, formato, enums, UUID, advice y errores seguros.
ApiHttpIntegrationTests usa puerto aleatorio, cliente HTTP Java, PostgreSQL
de Compose, esquema aislado y commits reales. No hay rollback de test que pretenda
revertir el trabajo confirmado por el servidor.

Se verifican creación atómica Account/LedgerAccount, saldo cero, relación con
Customer, Transfer y ledger, rechazo realmente confirmado/reproducido, rollback
técnico al commit, carreras de duplicados y fondos, key case-sensitive,
historial agregado/keyset, límites, EXPLAIN, OpenAPI y Swagger UI.
Los fondos provienen de journals técnicos de fixtures, sin caso de uso de depósito.
El esquema generado se elimina al finalizar; no se alteran datos de public.

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Resultado: **342 pruebas, 0 fallos, 0 errores, 0 omitidas**, BUILD SUCCESS y JAR
generado. Incluye las 267 pruebas previas y 75 de esta fase (14 mapping/cursor,
36 MVC y 25 HTTP/PostgreSQL). El documento generado por la prueba HTTP queda
en target/api-openapi.json. El JAR también arrancó con SERVER_ADDRESS=127.0.0.2,
SERVER_PORT=0 y OPENAPI_ENABLED/SWAGGER_UI_ENABLED=false: documentación y UI
devolvieron 404; PostgreSQL/Flyway V7 e Hibernate inicializaron correctamente.

Quedan pendientes ownership de transferencias y alcance de keys por identidad,
idempotencia para creación Customer/Account, snapshots históricos estrictos,
rendimiento a escala, separación de usuarios Flyway/runtime y CI/Testcontainers.
No se implementan depósitos, retiros, balance materializado, Kafka, Redis,
microservicios, refresh tokens ni endpoints de administración contable.
