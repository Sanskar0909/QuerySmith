-- Support report: the 10 customers with the most closed tickets.
SELECT c.email, count(*) AS closed_tickets
FROM support_tickets AS t
JOIN customers AS c ON c.id = t.customer_id
WHERE t.status = 'closed'
GROUP BY c.email
ORDER BY count(*) DESC
LIMIT 10;
