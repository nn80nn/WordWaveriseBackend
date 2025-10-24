# Database Setup Instructions

## PostgreSQL Configuration

### Option 1: Local PostgreSQL

1. Install PostgreSQL locally
2. Create database:
   ```sql
   CREATE DATABASE wordwaverise;
   ```
3. Update `.env` file with your local credentials:
   ```
   DB_URL=jdbc:postgresql://localhost:5432/wordwaverise
   DB_USER=postgres
   DB_PASSWORD=your_password
   ```

### Option 2: VPS PostgreSQL (Production)

1. Update `.env` file with your VPS credentials:
   ```
   DB_URL=jdbc:postgresql://your-vps-host:5432/wordwaverise
   DB_USER=your_db_username
   DB_PASSWORD=your_db_password
   ```

2. Make sure your VPS PostgreSQL allows remote connections:
   - Edit `postgresql.conf`: `listen_addresses = '*'`
   - Edit `pg_hba.conf`: Add line for your IP or allow all:
     ```
     host    all             all             0.0.0.0/0               md5
     ```

3. Restart PostgreSQL service

### Database Tables

Tables will be created automatically on first run:

- **users** table:
  ```sql
  CREATE TABLE users (
      id SERIAL PRIMARY KEY,
      email VARCHAR(255) UNIQUE NOT NULL,
      password_hash VARCHAR(255) NOT NULL,
      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
  );
  ```

- **saved_words** table:
  ```sql
  CREATE TABLE saved_words (
      id SERIAL PRIMARY KEY,
      user_id INTEGER REFERENCES users(id),
      word VARCHAR(255) NOT NULL,
      saved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      UNIQUE(user_id, word)
  );
  ```

## Environment Variables

Copy `.env.example` to `.env` and fill in your values:

```bash
cp .env.example .env
```

Then edit `.env` with your actual database credentials and JWT secret.

**IMPORTANT**: Never commit `.env` to git! It's already in `.gitignore`.

## Testing the Database Connection

Run the server:
```bash
./gradlew run
```

If the database connection is successful, you'll see tables created automatically.
If there's an error, check your database credentials in `.env`.
