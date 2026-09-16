# StudentBites

StudentBites is a server-rendered restaurant ordering application aimed at campus users. It provides account signup and login, a searchable menu, session-backed carts, authenticated checkout, persisted orders, printable invoices, and time-based order tracking through a Spring MVC and Thymeleaf interface.

## Technology stack

| Area | Technology |
| --- | --- |
| Language | Java 17 |
| Application framework | Spring Boot 3.3.5 |
| Web layer | Spring MVC, Thymeleaf |
| Persistence | Spring Data JPA, Hibernate |
| Validation | Jakarta Bean Validation |
| Databases | MySQL for the default profile; file-backed H2 for the `demo` profile |
| Build | Maven |
| Testing | JUnit 5, Spring Boot Test, MockMvc, AssertJ |
| Frontend | HTML, CSS, vanilla JavaScript |
| Password security | Spring Security Crypto, BCrypt with cost factor 12 |

## Implemented features

- Student signup, login, logout, normalized email handling, and duplicate-email protection.
- Menu browsing with category filters, text search, price sorting, and rating sorting.
- Add-to-cart, quantity updates, item removal by setting quantity to zero, cart totals, and cart count.
- Separate guest and authenticated-user carts within the HTTP session.
- Login-required checkout with account details applied on the server before order creation.
- Persisted orders and order lines with totals calculated from current menu prices.
- Pickup, dine-in, or hostel-delivery choices and simulated payment references.
- Printable invoice pages and order lookup restricted to the signed-in order owner.
- Account-specific display of the latest eight orders.
- Automatic status progression from Pending to Preparing, Ready, and Delivered.
- Seed data for menu items and homepage reviews.
- Responsive Thymeleaf pages with an H2-backed demo mode.

## Architecture

The application follows a conventional layered Spring MVC structure:

```text
Browser
  -> Spring MVC controllers
      -> application services
          -> Spring Data JPA repositories
              -> MySQL or H2
  <- Thymeleaf templates
```

- `controller`: handles HTTP routes, form binding, redirects, and view models.
- `service`: contains authentication, cart, checkout, and order-status logic.
- `repository`: provides JPA access for users, food items, orders, and reviews.
- `model`: defines persisted entities and status/category enums.
- `dto`: represents login, signup, checkout, and calculated cart data.
- `templates` and `static`: contain server-rendered pages and frontend assets.

## Data model

| Model | Purpose | Important fields and relationships |
| --- | --- | --- |
| `AppUser` | Student account | Unique normalized `email`, `fullName`, `phone`, optional `hostelOrClass`, `passwordHash` |
| `FoodItem` | Menu entry | `name`, `category`, `description`, `imageUrl`, positive `price`, `rating`, vegetarian flag, preparation time |
| `StudentOrder` | Checkout record | Student/contact details, order and payment modes, simulated payment reference, total, creation time, status; one-to-many `lines` |
| `OrderLine` | Item snapshot within an order | Many-to-one links to `StudentOrder` and `FoodItem`, plus quantity and calculated line total |
| `Review` | Seeded homepage testimonial | Student name, course, comment, rating |

`FoodCategory` contains the supported menu categories. `OrderStatus` defines `PENDING`, `PREPARING`, `READY`, and `DELIVERED`.

## Request flow

### Cart and checkout

1. Adding a menu item stores its ID and quantity in a `LinkedHashMap` held in the current `HttpSession`.
2. Cart display resolves those IDs through `FoodItemRepository` and calculates each line total from the current database price.
3. Checkout rejects an empty cart and redirects unauthenticated users to login.
4. For authenticated users, name, email, and phone are overwritten from the server-side session rather than trusted from submitted form fields.
5. `OrderService` creates a `StudentOrder`, adds cascading `OrderLine` records, calculates the total, assigns a simulated payment reference, and saves the aggregate.
6. The active user cart is cleared and the browser is redirected to the invoice.

### Order lifecycle

Orders start as `PENDING`. When an invoice, tracked order, or account order list is requested, the service derives the current status from elapsed time and persists forward-only transitions:

| Elapsed time | Status |
| --- | --- |
| Less than 1 minute | Pending |
| 1–2 minutes | Preparing |
| 3–5 minutes | Ready |
| 6 minutes or more | Delivered |

The invoice and tracking pages refresh periodically until delivery.

## Validation

- Login and signup require a valid, nonblank email and a nonblank password.
- Signup also requires a name and phone number; email uniqueness is checked in application logic and enforced by a database constraint.
- Checkout requires a student name and phone and validates the email format when supplied.
- `FoodItem` requires a nonblank name and a positive, non-null price.
- Cart quantities are limited to 99 per item; zero removes an item. Negative quantities, overflow attempts, and unknown item IDs are rejected.
- Checkout accepts only the order and payment modes displayed in its dropdowns.
- `AppUser` and `StudentOrder` also carry entity-level Bean Validation constraints.

## Setup and running

### Prerequisites

- JDK 17 or later
- Maven 3.9 or later
- MySQL 8.x only when using the default profile

### Quick start with H2

The demo profile uses a file-backed H2 database under `data/`, so no external database is required.

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=demo"
```

Alternatively, on Windows:

```powershell
.\run-demo.ps1
```

The helper script uses port `8081`. Without the script, the application uses `http://localhost:8080` unless `PORT` is set.

### Run with MySQL

The application reads its connection settings from environment variables and uses the shown defaults when they are absent:

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/studentbites?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:DB_USER = "your_mysql_username"
$env:DB_PASSWORD = "your_mysql_password"
mvn spring-boot:run
```

Override the port if necessary:

```powershell
$env:PORT = "8091"
mvn spring-boot:run "-Dspring-boot.run.profiles=demo"
```

### Run tests

```powershell
mvn test
```

The integration suite uses an in-memory H2 database and covers normal ordering flows plus invoice ownership, guest tracking rejection, quantity boundaries, invalid checkout modes, session rotation/logout, and password hash migration.

## Design decisions

### Server-rendered MVC instead of a separate SPA

Spring MVC and Thymeleaf keep navigation, validation errors, and form processing in one Java application. A React/Vue frontend plus REST API would support richer client-side interactions, but would add a second build and API contract without being necessary for this project scope.

### Session-backed cart instead of a cart table

The cart stores only `foodItemId -> quantity` entries in `HttpSession`. This avoids persisting incomplete shopping activity and keeps cart operations small. Guest carts use `cart:guest`; authenticated carts use `cart:user:<normalized-email>`, preventing guest and account data from being mixed inside the same browser session. The guest cart is deliberately cleared during login/signup instead of being merged, which favors predictable account isolation over cart continuity.

A database-backed cart was not selected because it would require cart ownership, expiration, and cleanup rules. The tradeoff is that the current cart is tied to one server session and is not shared across devices; clustered deployment would also require shared session storage or sticky sessions.

### Persisted order aggregate

`StudentOrder` owns its `OrderLine` collection with cascade and orphan removal, allowing checkout to save the order and its lines together. Each line stores its calculated total so the invoice retains the amount charged even if a menu price later changes. A fully denormalized order-item snapshot was not used, so item names still come from the referenced `FoodItem`.

### Derived, forward-only order status

Status is calculated from order age when orders are viewed, then persisted only if it moves forward. This provides a deterministic demo without schedulers or kitchen-worker tooling. A scheduled job or event-driven workflow would be more appropriate for real fulfilment because this implementation does not represent actual preparation events.

### H2 demo profile plus MySQL default

The H2 profile makes the project runnable without infrastructure and uses MySQL compatibility mode. MySQL remains the default runtime database to demonstrate external relational database configuration. This is convenient for development, although database-specific behavior should still be verified against MySQL before deployment.

## Current limitations

- Authentication uses custom session handling. Spring Security Crypto provides BCrypt password hashing; the full Spring Security authentication framework is not configured.
- New passwords use Spring Security Crypto's BCrypt encoder with a random salt and cost factor 12. Existing SHA-256 and PBKDF2 hashes upgrade after successful login. Passwords are limited to 72 UTF-8 bytes for BCrypt; longer legacy passwords remain usable with their existing hash to avoid truncation. Login/signup rotate the session ID, and logout invalidates the session and its carts.
- There are no roles, admin dashboard, or kitchen workflow. Invoice and order-ID lookup require the owning account.
- Payment is simulated; no payment gateway or transaction verification exists.
- Order status is elapsed-time simulation, not real operational state.
- Carts are session-local and are not persisted or shared across devices.
- Reviews are seeded for display; users cannot submit or moderate them.
- There is no REST API, email/SMS notification, container configuration, CI workflow, or deployment setup.
- The test suite is integration-focused and does not cover every validation branch or security boundary.

## Repository size

Maven output (`target/`), runtime data, logs, IDE metadata, and local environment files are ignored. Most of the repository size comes from the tracked PNG menu images under `src/main/resources/static/images/menu-photos/`, which are application assets rather than build artifacts.
