# Seguridad: Fase 7A

Fase 7A incorpora identidad independiente, login y JWT, ownership de lecturas
de Customers/Accounts y creación administrativa. PostgreSQL y el ledger siguen
siendo la fuente de verdad. No hay refresh, logout, registro público, gestión
HTTP de roles, recuperación de contraseñas, impersonación ni rate limiting.

## Permisos

| Método y ruta | Permiso |
|---|---|
| POST /api/v1/auth/login | Público |
| GET /api/v1/auth/me | Autenticado |
| POST /api/v1/customers | ADMIN |
| POST /api/v1/accounts | ADMIN |
| GET /api/v1/customers/{id} | USER, Customer propio |
| GET /api/v1/accounts/{id} | USER, cuenta propia |
| GET /api/v1/accounts/{id}/balance | USER, cuenta propia |
| GET /api/v1/accounts/{id}/transactions | USER, cuenta propia |
| POST /api/v1/transfers | Autenticado; ownership pendiente de 7B |
| GET /api/v1/transfers/{id} | Autenticado; ownership pendiente de 7B |

ADMIN no implica USER. Con ambos roles, las lecturas siguen restringidas por
ownership. Recursos ajenos e inexistentes producen el mismo 404. Las demás
rutas/métodos se deniegan. No se configura CORS abierto, Basic ni form login.

**Transferencias aún no están aisladas por identidad.** Una identidad autenticada
puede acceder a las operaciones actuales de transferencias, incluidas cuentas
ajenas. Su idempotencia sigue siendo global. Esto es una limitación explícita
de 7A: no exponer FinCore a usuarios no confiables hasta completar 7B.
TransferService, sus guards, V7 y sus límites transaccionales permanecen intactos.

## Identidad y passwords

V8 crea `auth_users` y `auth_user_roles`. Login normalizado y Customer asociado
tienen unicidad PostgreSQL. Los roles son USER y ADMIN. USER exige Customer
explícito en dominio/aprovisionamiento; un ADMIN operativo puede no tenerlo.
Customer.email continúa siendo contacto; nunca se vinculan identidades por
coincidencia de emails.

`AuthUserProvisioningService.provision(email, password, customerId, roles)` es
un servicio interno reutilizable, sin endpoint HTTP. Comprueba Customers,
roles y colisiones; nunca reemplaza credenciales existentes. Passwords de
12–128 caracteres se conservan exactamente, incluidos espacios.

Argon2id usa DelegatingPasswordEncoder y Argon2Password4jPasswordEncoder,
19 MiB, 2 iteraciones y paralelismo 1; calibrar costes para el hardware de
producción. Password4j 1.8.4 coincide con la dependencia utilizada por
Spring Security 7.1.1. PostgreSQL almacena solo el hash con salt aleatorio y
prefijo `{argon2id}`. No se registran passwords, JWT ni Authorization.

## Claves externas y JWT

La aplicación web exige una pareja RSA válida de al menos 2048 bits:

- `FINCORE_JWT_PRIVATE_KEY`: URI `file:` de PEM PKCS8, BEGIN PRIVATE KEY.
- `FINCORE_JWT_PUBLIC_KEY`: URI `file:` de PEM X509, BEGIN PUBLIC KEY.

Los archivos deben estar fuera del repositorio y protegidos por permisos del
sistema operativo. No hay claves predeterminadas. No se generan claves nuevas
en cada arranque. Las claves de default y dev deben ser distintas.

Ejemplo de generación local con OpenSSL, después de crear una carpeta externa:

```powershell
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$env:USERPROFILE/.fincore/default-private.pem"
openssl pkey -in "$env:USERPROFILE/.fincore/default-private.pem" -pubout -out "$env:USERPROFILE/.fincore/default-public.pem"
$env:FINCORE_JWT_PRIVATE_KEY = ([Uri]::new("$env:USERPROFILE/.fincore/default-private.pem")).AbsoluteUri
$env:FINCORE_JWT_PUBLIC_KEY = ([Uri]::new("$env:USERPROFILE/.fincore/default-public.pem")).AbsoluteUri
```

Generar otra pareja para dev y seleccionar sus URI antes de arrancar ese perfil.
No reutilizar claves de test. La suite genera claves temporales y las elimina
al terminar; no se incluyen claves en Git ni en el JAR.

| Configuración | Default | dev |
|---|---|---|
| Base | POSTGRES_DB, fallback fincore | POSTGRES_DEV_DB, fallback fincore_dev |
| Issuer | fincore | fincore-dev |
| Audience | fincore-api | fincore-api-dev |
| Flyway | db/migration | db/migration y db/dev-migration |

Firma RS256, duración 900 segundos y tolerancia de reloj de 30 segundos.
Se exige sub UUID, iss, aud, iat, nbf, exp, jti UUID y ver numérico entero.
Se validan firma, algoritmo, issuer, audience, vigencia y coherencia temporal.
No se incluyen roles, customerId, email, nombres, cuentas, balances ni hashes.

Tras validar el JWT, cada petición lee la identidad, estado, securityVersion,
roles y customerId desde PostgreSQL. CurrentActor es inmutable y procede solo
del contexto de seguridad. DISABLED o una versión revocada invalida el token.
La aplicación no mantiene sesiones HTTP; no emite cookies de autenticación.
Desactivar/revocar afecta a siguientes peticiones, no cancela trabajo en curso.

Login recibe `{ "email": "...", "password": "..." }` y responde
`{ "accessToken": "...", "tokenType": "Bearer", "expiresIn": 900 }`.
Usar TLS fuera de desarrollo local y `Authorization: Bearer <token>`.
`/auth/me` devuelve solo id de identidad, customerId y roles. Todas las
respuestas de API mantienen X-Request-Id y Cache-Control: no-store.

Sin token: 401 AUTHENTICATION_REQUIRED. Token inválido, expirado o revocado:
401 INVALID_TOKEN. Login incorrecto/deshabilitado: 401 INVALID_CREDENTIALS.
Rol insuficiente: 403 FORBIDDEN. Se conserva ApiError y el path seguro.
Los errores Bearer incluyen WWW-Authenticate.

## Primer ADMIN

Empaquetar con `./mvnw` o `.\mvnw.cmd -B -ntp clean verify` y ejecutar el JAR
en una terminal interactiva. Este modo explícito no inicia un servidor HTTP
y no necesita claves JWT:

```powershell
$env:FINCORE_ADMIN_EMAIL = 'admin@example.test'
java -jar target/fincore-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none --spring.profiles.active=provision-admin --fincore.demo.seed.enabled=false
```

Solicita password y confirmación sin eco, crea únicamente ADMIN sin Customer
y muestra el UUID. No pasar passwords por argumentos. Repetir el comando
para el mismo login falla sin cambiar el hash. Para la base dev, usar
`--spring.profiles.active=dev,provision-admin --fincore.demo.seed.enabled=false`.
El runner nunca se registra en un servidor HTTP, aunque se active su perfil.

## Usuarios demo y Swagger

Ver [demo-seed.md](demo-seed.md). Solo dev más seed.enabled=true provisiona
Alejandro y Fernando como USER mediante los Customer IDs de starter-mxn-v1.
Sus passwords deben suministrarse externamente en la primera creación.
Los reinicios recuperan los usuarios, sin resetear hashes ni reponer dinero.

Swagger y /v3/api-docs se permiten únicamente en dev; el botón Authorize usa
Bearer JWT. Login aparece público. Activar springdoc en default no concede
permiso HTTP para sus rutas. Los flags no cambian los permisos de la API.

## Validación y límites siguientes

Tests HTTP usan JWT reales y la cadena completa. Las pruebas PostgreSQL usan
esquemas temporales; la suite prueba upgrade V7→V8, constraints, login, claims,
firma inválida, revocación, roles, ownership, errores, OpenAPI y reinicio demo
en modo read-only con credenciales externas distintas.

7B debe resolver ownership de transferencias y sus replays, y el alcance de
idempotencia por identidad. Rate limiting, refresh, password recovery y
gestión de roles no forman parte de 7A.

Resultado verificado con `.\mvnw.cmd -B -ntp clean verify`: **416 pruebas,
0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Son las 366 pruebas previas
adaptadas más 50 nuevas. JAR: `target/fincore-0.0.1-SNAPSHOT.jar`.
