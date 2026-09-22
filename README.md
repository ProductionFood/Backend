# Backend — ProductionFood

API REST para el sistema de gestión de producción de panadería.

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

Crear variables de entorno:

```bash
export DB_HOST=localhost
export DB_USER=root
export DB_PASSWORD=local
export JWT_SECRET=$(openssl rand -base64 48)
```

## Ejecutar

```bash
mvn clean install
mvn spring-boot:run
```

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
