-- Add authentication and role columns to customers and payment_method to orders
ALTER TABLE customers ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
ALTER TABLE customers ADD COLUMN IF NOT EXISTS role VARCHAR(50) NOT NULL DEFAULT 'CUSTOMER';
ALTER TABLE customers ALTER COLUMN role TYPE VARCHAR(50);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS payment_method VARCHAR(30) DEFAULT 'UPI';

-- Remove any legacy seed data if present
DELETE FROM notifications;
DELETE FROM payments;
DELETE FROM inventory_reservations;
DELETE FROM order_items;
DELETE FROM orders;
DELETE FROM outbox_events;
DELETE FROM processed_events;
DELETE FROM products;
DELETE FROM customers;

-- Seed only Admin and Inventory Manager accounts (default password on first login: password123)
INSERT INTO customers (id, name, email, role, created_at) VALUES
('a1111111-1111-1111-1111-111111111111', 'Admin', 'admin@order.com', 'ADMIN', now()),
('a2222222-2222-2222-2222-222222222222', 'Inventory Manager', 'inventory.manager@order.com', 'INVENTORY_MANAGER', now())
ON CONFLICT (id) DO UPDATE
SET name = EXCLUDED.name,
    email = EXCLUDED.email,
    role = EXCLUDED.role;
