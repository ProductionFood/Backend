# Backend — ProductionFood

API REST para el sistema de gestión de productora de alimentos.

## Stack

| Capa | Tecnología |
|---|---|
| Java | 25 LTS |
| Framework | Spring Boot 4.1.1 |
| Seguridad | Spring Security 7 + JWT (jjwt 0.13.0) |
| Persistencia | Spring Data JPA (Hibernate) |
| Base de datos | MySQL 8.4 LTS (AWS RDS) |
| Documentación | SpringDoc OpenAPI 3.1.1 |

## Requisitos

- JDK 25 (Temurin)
- Maven 3.9.x
- MySQL 8.4

## Configuración

El backend lee la configuración desde un archivo `.env` en la raíz del proyecto
(`springboot4-dotenv`). Las variables de entorno reales del sistema tienen
prioridad sobre el `.env`, así que en producción basta con definirlas sin
archivo.

1. Copia la plantilla y ajusta los valores:

   ```bash
   cp .env.example .env
   ```

2. Guarda el `.env` como **UTF-8 sin BOM** y con saltos de línea **LF**. Un BOM
   invisible al inicio hace que `dotenv` ignore la primera variable en silencio.

| Variable | Default | Descripción |
|---|---|---|
| `DB_HOST` | `127.0.0.1` | Host de MySQL. Usa `127.0.0.1`, no `localhost` (ver Problemas comunes). |
| `DB_PORT` | `3306` | Puerto de MySQL. |
| `DB_NAME` | `productionfood` | Base creada por Compose y migrada por Flyway. |
| `DB_USER` | `root` | Usuario de MySQL. |
| `DB_PASSWORD` | `local` | Contraseña de root; debe coincidir con la de Compose. |
| `JWT_SECRET` | — | Secreto para firmar los JWT. |

## Ejecutar

```bash
# 1. Levanta MySQL (y phpMyAdmin en http://localhost:8081)
docker compose up -d

# 2. Arranca el backend (Flyway construye el esquema al inicio)
mvn clean install
mvn spring-boot:run
```

## Problemas comunes

- **El backend arranca pero no conecta a la base de datos.** Confirma que el
  `.env` esté en la raíz del proyecto y que `DB_PASSWORD` coincida con la
  contraseña de MySQL del contenedor. Tras cambiar el `.env`, recrea el
  contenedor con `docker compose down -v && docker compose up -d` (el volumen
  conserva la contraseña anterior).
- **`Access denied for user 'root'`.** El backend está cayendo a los defaults en
  lugar de leer el `.env` (contraseña `local`). Revisa la codificación del
  archivo (BOM) y que exista.
- **En Windows no conecta aunque el `.env` sea correcto.** `localhost` puede
  resolverse a IPv6 (`::1`) y Docker Desktop no siempre publica el puerto en
  IPv6. Usa `DB_HOST=127.0.0.1`.

## Endpoints

- API: `http://localhost:8080`
- Swagger: `http://localhost:8080/swagger-ui.html`

## Estructura

```
src/main/java/co/edu/corposucre/productionfood/
├── config/          SecurityConfig, OpenApiConfig
├── security/        JWT, filtros, autenticación
├── common/          Errores, paginación
├── rol/             Módulo de roles
└── usuario/         Módulo de usuarios (HU-01)
```

## Frontend

El frontend está en un repositorio separado (Angular 22 + Material).
