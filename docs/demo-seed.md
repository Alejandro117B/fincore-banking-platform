# Fondos demo de desarrollo

El único escenario es `starter-mxn-v1`:

| Customer | Account | Moneda | Saldo inicial esperado |
|---|---|---|---:|
| Alejandro Demo | CHECKING | MXN | 2000.0000 |
| Fernando Demo | SAVINGS | MXN | 0.0000 |

## Activación

Usar una base o esquema exclusivo de desarrollo, con la conexión PostgreSQL
configurada mediante las variables habituales de FinCore. El perfil `dev`
incorpora una migración técnica adicional; otros perfiles siguen cargando solo
`classpath:db/migration`. No compartir el historial Flyway de desarrollo con
un entorno que no carga las migraciones de desarrollo.

Ambas condiciones son obligatorias: perfil `dev` y propiedad
`fincore.demo.seed.enabled=true`. La propiedad es `false` por defecto.

En PowerShell:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:FINCORE_DEMO_SEED_ENABLED = 'true'
.\mvnw.cmd spring-boot:run
```

También se puede configurar `fincore.demo.seed.enabled=true` como propiedad
Spring. La variable `FINCORE_DEMO_SEED_ENABLED` está conectada explícitamente a
esa propiedad en `application-dev.yml`, incluyendo el soporte del `.env` local.
Activar solamente la propiedad fuera de `dev` no registra el seeder.

Para desactivar en la misma sesión:

```powershell
$env:FINCORE_DEMO_SEED_ENABLED = 'false'
Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
```

El perfil `dev` carga `classpath:db/dev-migration` aunque el seeder esté
deshabilitado. En ese caso solo existe la tabla técnica vacía: no se crean
Customers, Accounts ni fondos.

## IDs y reinicios

Los UUID los generan los servicios y las fábricas existentes al crear el
escenario. No tienen valores fijos entre bases distintas. Los cuatro UUID de
Customers y Accounts, el UUID del journal y la huella SHA-256 de la definición
se conservan en `demo_seed_runs` bajo la PK `scenario_key`.

El log de arranque exitoso muestra los cuatro IDs, el journal y el saldo inicial
esperado de Alejandro. Se registra después de confirmar la transacción. El
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
Cubren activación, aislamiento de migraciones, arranque, entries y balances,
reinicios antes y después de gastar, espera concurrente del advisory lock,
rollback después del posting y al commit, conflicto de huella, reutilización o
rechazo de la contrapartida y logs de IDs.
