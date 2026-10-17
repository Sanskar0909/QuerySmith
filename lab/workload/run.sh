#!/bin/sh
# Plays the shop's traffic against the lab database, as the shop_app login.
#
#   lab/workload/run.sh          run for 60 seconds
#   lab/workload/run.sh 300      run for 300 seconds
#
# pgbench is Postgres's own load generator and already ships inside the
# database image. Each --file is one kind of request the shop makes. The number
# after @ is how often it is picked relative to the others: the product listing
# is the busiest page, the finance reports are rare.
#
# Four clients run at once, each picking a request, waiting for the answer, and
# picking the next.
set -eu

seconds="${1:-60}"
cd "$(dirname "$0")/../.."

# "docker compose exec" wants a terminal unless told otherwise with -T.
tty=""
[ -t 0 ] || tty="-T"

exec docker compose exec $tty db pgbench \
  --no-vacuum --username=shop_app \
  --client=4 --jobs=2 --time="$seconds" --progress=10 \
  --file=/lab/workload/q06_product_listing.sql@60 \
  --file=/lab/workload/q01_orders_by_customer.sql@12 \
  --file=/lab/workload/q05_customer_by_email.sql@10 \
  --file=/lab/workload/q02_order_items.sql@8 \
  --file=/lab/workload/w01_place_order.sql@4 \
  --file=/lab/workload/w02_ship_order.sql@4 \
  --file=/lab/workload/q03_pending_queue.sql@3 \
  --file=/lab/workload/q09_daily_sales.sql@2 \
  --file=/lab/workload/q10_orders_page.sql@2 \
  --file=/lab/workload/q04_delivered_revenue.sql@1 \
  --file=/lab/workload/q07_export_by_value.sql@1 \
  --file=/lab/workload/q08_closed_tickets.sql@1 \
  shop
