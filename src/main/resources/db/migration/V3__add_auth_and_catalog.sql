-- Add authentication and role columns to customers
ALTER TABLE customers ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
ALTER TABLE customers ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER';

-- Ensure existing seeded customers have roles set
UPDATE customers SET role = 'CUSTOMER' WHERE id = 'a0000000-0000-0000-0000-000000000001';
UPDATE customers SET role = 'CUSTOMER' WHERE id = 'a0000000-0000-0000-0000-000000000002';

-- Seed Admin and additional Enterprise Customers
INSERT INTO customers (id, name, email, role, created_at) VALUES
('a1111111-1111-1111-1111-111111111111', 'Asha Mehta', 'asha.mehta@example.com', 'ADMIN', now()),
('a2222222-2222-2222-2222-222222222222', 'Rohan Kapoor', 'rohan.kapoor@example.com', 'CUSTOMER', now()),
('a3333333-3333-3333-3333-333333333333', 'Priya Nair', 'priya.nair@example.com', 'CUSTOMER', now()),
('a4444444-4444-4444-4444-444444444444', 'Vikram Sen', 'vikram.s@logistics.net', 'CUSTOMER', now())
ON CONFLICT (id) DO NOTHING;

-- Seed additional Catalog Products
INSERT INTO products (id, name, category, price, stock_quantity) VALUES
('b0000000-0000-0000-0000-000000000005', '27" 4K Monitor', 'Electronics', 15999.00, 25),
('b0000000-0000-0000-0000-000000000006', 'USB-C Multiport Hub', 'Electronics', 1299.00, 200),
('b0000000-0000-0000-0000-000000000007', 'Ergonomic Office Chair', 'Furniture', 8999.00, 15),
('b0000000-0000-0000-0000-000000000008', 'Motorized Standing Desk', 'Furniture', 21999.00, 8),
('b0000000-0000-0000-0000-000000000009', 'Executive Notebook Set', 'Stationery', 249.00, 500),
('b0000000-0000-0000-0000-000000000010', 'Brass Fountain Pen', 'Stationery', 899.00, 90)
ON CONFLICT (id) DO NOTHING;
