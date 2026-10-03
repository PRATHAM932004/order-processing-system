-- Seed Customers
INSERT INTO customers (id, name, email, created_at) VALUES
('a0000000-0000-0000-0000-000000000001', 'Alice Johnson', 'alice@example.com', now()),
('a0000000-0000-0000-0000-000000000002', 'Bob Smith', 'bob@example.com', now());

-- Seed Products
INSERT INTO products (id, name, category, price, stock_quantity) VALUES
('b0000000-0000-0000-0000-000000000001', 'Wireless Mouse', 'Electronics', 799.00, 100),
('b0000000-0000-0000-0000-000000000002', 'Mechanical Keyboard', 'Electronics', 3499.00, 50),
('b0000000-0000-0000-0000-000000000003', 'USB-C Cable', 'Accessories', 299.00, 200),
('b0000000-0000-0000-0000-000000000004', 'Limited Edition Headphones', 'Audio', 8999.00, 2);
