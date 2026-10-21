# Seguridad de transferencias e idempotencia por identidad: Fase 7B

POST /api/v1/transfers requiere USER y ownership actual del origen. El destino
puede pertenecer a otro Customer. GET /api/v1/transfers/{id} requiere USER y
participación actual como propietario del origen o destino. ADMIN no implica
USER y no evita ownership. Requester es exclusivamente CurrentActor.userId,
obtenido del JWT validado y de AuthUser en PostgreSQL.

## Persistencia y migración V9

V9 añade requester_user_id UUID nullable con FK auth_users(id) ON DELETE
RESTRICT. Las filas previas conservan NULL y todos sus valores originales;
no se recalculan hashes ni se atribuyen operaciones anónimas. La única
unicidad retirada es la global de key; se añade UNIQUE NULLS NOT DISTINCT
(requester_user_id, idempotency_key), inmediata y utilizable por ON CONFLICT.
El índice cubre búsqueda por actor/key y detección del namespace NULL.

El guard de INSERT exige RESERVED y requester no NULL. Si la key existe en
el namespace histórico, rechaza con SQLSTATE interno P7001, traducido a
409 IDEMPOTENCY_KEY_UNAVAILABLE. El mensaje externo es genérico, sin IDs,
status, hashes ni datos financieros y sin sugerir otra key. El UPDATE también
declara requester inmutable, incluido NULL histórico. Los guards originales
de estados terminales, DELETE/TRUNCATE, commit no RESERVED y correspondencia
Transfer/journal/entries permanecen activos. Las consultas de triggers usan
TG_TABLE_SCHEMA, incluso con search_path de otro esquema.

Las Transfers históricas siguen disponibles por GET a participantes actuales.
Sus keys no permiten replay ni operaciones nuevas autenticadas. Detener y
drenar instancias 7A antes de desplegar V9/7B: el binario anterior no aporta
requester y sus nuevos INSERT ya no son válidos. No hacer repair, backfill
de identidades, cambios a V1–V8 ni despliegue mixto de escritores 7A/7B.

## Ejecución y replay

1. JWT → CurrentActor → USER → normalización económica existente.
2. TransactionTemplate propio READ_COMMITTED, timeout 15 s y lock_timeout 10 s.
3. Ownership inicial sin lock anticipado; detectar key retirada y reservar
   actor/key mediante INSERT ON CONFLICT DO NOTHING RETURNING id.
4. Tras una posible espera, repetir ownership. Una reserva previa exige mismo
   actor/key, hash y HASH_VERSION=1. SUCCEEDED se proyecta desde esa reserva
   y origen propio; REJECTED reproduce el rechazo original; RESERVED es una
   inconsistencia técnica. No se ejecuta dinero ni se valida saldo/estado al
   reproducir. Reference permanece fuera del hash; requester pertenece al scope.
5. Para operación nueva: Account locks en orden canónico, verificar ownership
   del origen bloqueado, después LedgerAccount locks en orden canónico.
6. Validaciones financieras existentes → LedgerPostingService → Transfer →
   finalizar por reservation ID y requester → commit.

Rechazo de seguridad: rollback, sin reserva nueva ni journal. Rechazo financiero
autorizado: REJECTED confirmado antes de comunicar el error; fondeo posterior
no lo transforma en éxito. Fallo técnico: rollback completo de lo nuevo y key
reutilizable. Nunca se modifica ni elimina un registro terminal existente.
No hay transacción exterior ni retry automático.

GET utiliza proyección filtrada por transferId y Customer participante antes
de construir la respuesta. Es independiente del replay: ser destinatario no
permite reclamar el POST original. Si en el futuro cambia el titular de una
Account, GET sigue a los participantes actuales y replay requiere que el
requester original conserve el origen; la reserva nunca cambia de identidad.

## Contrato externo y validación

- Sin token / token inválido: 401 AUTHENTICATION_REQUIRED / INVALID_TOKEN.
- Sin USER: 403 FORBIDDEN.
- Origen ajeno o inexistente: 404 ACCOUNT_NOT_FOUND.
- Transfer ajena o inexistente: 404 TRANSFER_NOT_FOUND.
- Mismo actor/key con request distinto: 409 IDEMPOTENCY_CONFLICT.
- Key no disponible: 409 IDEMPOTENCY_KEY_UNAVAILABLE, mensaje genérico.
- Rechazos financieros y errores técnicos conservan códigos/status existentes.

ApiError, X-Request-Id, no-store y Bearer JWT permanecen. OpenAPI describe roles,
ownership y los conflictos. Las pruebas usan JWT reales en HTTP, PostgreSQL
real, esquemas temporales y contextos explícitos por worker en concurrencia.
Cubren migración V8→V9 con historia de éxito/rechazo, guards, ownership, replays,
namespaces, rollbacks, locks y conservación de demo/default/dev.

Verificación final: `.\mvnw.cmd -B -ntp clean verify` terminó con BUILD SUCCESS:
440 pruebas, 0 failures, 0 errors, 0 skipped (416 previas y 24 nuevas).

No se añaden endpoints, balances mutables, permisos administrativos financieros,
refresh, logout, rate limiting ni gestión de titularidad. Esas capacidades
requieren diseño y autorización independientes.
