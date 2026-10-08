# Fondos demo de desarrollo

El único escenario es `starter-mxn-v1`:

| Customer | Account | Moneda | Saldo inicial esperado |
|---|---|---|---:|
| Alejandro Demo | CHECKING | MXN | 2000.0000 |
| Fernando Demo | SAVINGS | MXN | 0.0000 |

## Activación

Configurar `POSTGRES_DB=fincore` y `POSTGRES_DEV_DB=fincore_dev` en `.env`.
Default se conecta a `fincore`; dev sobrescribe únicamente la URL para conectarse
a `fincore_dev`. Ambos reutilizan las variables de host, puerto, usuario y
contraseña. Las bases deben existir previamente: ni la aplicación ni Flyway
crean bases de datos; Compose no crea una segunda base en el volumen existente.

Default carga solo `classpath:db/migration`. El perfil `dev` utiliza esa misma
ubicación y añade `classpath:db/dev-migration`. Cada base conserva su propio
historial Flyway; no compartir el historial demo con default.

Ambas condiciones son obligatorias: perfil `dev` y propiedad
`fincore.demo.seed.enabled=true`. La propiedad es `false` por defecto.

En PowerShell:

Antes del primer arranque habilitado, configurar las claves RSA externas de dev
y suministrar `FINCORE_DEMO_ALEJANDRO_PASSWORD` y
`FINCORE_DEMO_FERNANDO_PASSWORD` por configuración externa (12–128 caracteres).
No escribir contraseñas en argumentos ni archivos versionados. Ver
[security-7a.md](security-7a.md) para claves y aprovisionamiento.

Los logins predeterminados son `alejandro.demo@example.test` y
`fernando.demo@example.test`; pueden fijarse mediante
`FINCORE_DEMO_ALEJANDRO_EMAIL` y `FINCORE_DEMO_FERNANDO_EMAIL` en la primera
creación. Se asocian exclusivamente a los Customer IDs persistidos, con rol USER.
Un reinicio conserva UUIDs y hashes aunque cambien los passwords externos.
Cambiar el login configurado de una identidad ya creada falla sin reasociarla.
No se crea ADMIN demo. La definición financiera y su hash permanecen intactos.

```powershell
.\mvnw.cmd "-Dspring-boot.run.arguments=--spring.profiles.active=dev --fincore.demo.seed.enabled=true" spring-boot:run
```

También se puede configurar `fincore.demo.seed.enabled=true` como propiedad
Spring. La variable `FINCORE_DEMO_SEED_ENABLED` está conectada explícitamente a
esa propiedad en `application-dev.yml`, incluyendo el soporte del `.env` local.
Activar solamente la propiedad fuera de `dev` no registra el seeder.

Para arrancar dev con el seeder deshabilitado:

```powershell
.\mvnw.cmd "-Dspring-boot.run.arguments=--spring.profiles.active=dev --fincore.demo.seed.enabled=false" spring-boot:run
```

El perfil `dev` carga `classpath:db/dev-migration` aunque el seeder esté
deshabilitado. No se crean Customers, Accounts ni fondos; si la base ya contiene
el escenario, sus IDs, movimientos y saldos actuales se conservan.

Para volver al entorno default, incluso después de haber usado dev:

```powershell
.\mvnw.cmd "-Dspring-boot.run.arguments=--spring.profiles.active= --fincore.demo.seed.enabled=false" spring-boot:run
```

## IDs y reinicios

Los UUID los generan los servicios y las fábricas existentes al crear el
escenario. No tienen valores fijos entre bases distintas. Los cuatro UUID de
Customers y Accounts, el UUID del journal y la huella SHA-256 de la definición
se conservan en `demo_seed_runs` bajo la PK `scenario_key`.

El log de arranque exitoso muestra los cuatro IDs, el journal y el saldo inicial
esperado de Alejandro, además de los dos AuthUser IDs, sin passwords ni tokens.
Se registra después de confirmar las transacciones. El
importe esperado describe el escenario original, no el saldo actual.

Para recuperar los IDs con una conexión SQL de desarrollo:

```sql
SELECT scenario_key, definition_hash,
       alejandro_customer_id, alejandro_account_id,
       fernando_customer_id, fernando_account_id,
       funding_journal_id, created_at
FROM demo_seed_runs
WHERE scenario_key = 'starter-mxn-v1';
```

Un reinicio con la misma definición devuelve los mismos IDs y no crea datos ni
dinero. Si Alejandro transfiere 500 MXN a Fernando, los saldos de 1500 y 500 MXN
se conservan en los siguientes arranques. Una huella diferente causa un error
explícito y no aplica fondos adicionales.

## Contabilización y atomicidad

Los Customers y Accounts se crean con `CustomerService` y `AccountService`.
La contrapartida `DEMO_CASH_MXN` es una LedgerAccount interna ASSET en MXN;
una existente solo se reutiliza si sus metadatos son compatibles.

El fondeo consiste en un JournalTransaction real con DEBIT de 2000.0000 a
`DEMO_CASH_MXN` y CREDIT de 2000.0000 al ledger LIABILITY de Alejandro. Se
contabiliza exclusivamente con `LedgerPostingService`. Fernando empieza sin
movimientos. La tabla técnica no almacena balances y no se agregan endpoints.

Un advisory transaction lock de PostgreSQL serializa las ejecuciones. La
reserva técnica, creación de clientes y cuentas, posting y finalización de los
IDs se ejecutan en una sola transacción READ COMMITTED. El posting conserva su
propagación existente y participa en esa transacción. Un error, incluso al
commit, revierte todo y permite reintentar en el siguiente arranque.

## Verificación

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Las pruebas del seeder usan PostgreSQL real en esquemas temporales aislados.
La suite fija `POSTGRES_DB=fincore` y `POSTGRES_DEV_DB=fincore_dev`; Surefire
excluye las variables heredadas `SPRING_PROFILES_ACTIVE` y
`FINCORE_DEMO_SEED_ENABLED` y neutraliza los flags del `.env` solo para los tests.
Los casos dev y los casos de seeding optan explícitamente por su configuración.
Las pruebas de reinicio abren contextos Spring reales y comprueban el catálogo
PostgreSQL, la URL JDBC efectiva y la conservación de los datos entre arranques.
Cubren activación, aislamiento de migraciones, arranque, entries y balances,
reinicios antes y después de gastar, espera concurrente del advisory lock,
rollback después del posting y al commit, conflicto de huella, reutilización o
rechazo de la contrapartida y logs de IDs.
