-- Login: find the customer by the email they typed, ignoring upper/lower case.
\set n random(1, 100000)
SELECT id, full_name
FROM customers
WHERE lower(email) = 'user:n@example.com';
