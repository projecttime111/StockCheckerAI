# StockPilot AI Local Testing Guide

This guide walks through the full demo on web and mobile.

## 1. Start the Backend and Demo Data

Preferred path:

```bash
docker compose -p stockpilot-demo -f docker-compose.yml -f docker-compose.demo.yml up --build
```

This explicit local demo override seeds the demo tenant on startup. The ordinary
`docker compose up --build` command keeps seeding disabled for real import work.
Use the separate `stockpilot-demo` project shown above to isolate demo volumes.
Do not enable demo seeding on a hosted or client-data environment.

Demo users all use password: `password123`

| Role to test | Email |
| --- | --- |
| Owner | `owner@demo.com` |
| Admin | `admin@demo.com` |
| Manager | `manager@demo.com` |
| Warehouse staff | `warehouse@demo.com` |
| Sales staff | `sales@demo.com` |
| Purchase manager | `purchase@demo.com` |
| Accountant | `accountant@demo.com` |
| Viewer | `viewer@demo.com` |
| Auditor | `auditor@demo.com` |
| Dealer/customer portal | `ravi@demo.com` |

Backend URLs:

- API: `http://127.0.0.1:8080`
- Swagger: `http://127.0.0.1:8080/swagger-ui.html`
- Adminer: `http://127.0.0.1:8081`

The seeder is idempotent when the demo override is enabled. A restart without
that override does not create demo accounts or records. For a real-data demo,
register your own account and import the prepared source files instead.

## 2. Start the Web App

Temporary web-only demo mode, no Docker/backend/PostgreSQL:

```bash
start-web-demo.bat
```

This mode uses in-browser dummy API responses so you can test the web UI quickly. It is not connected to the Java backend.

Windows shortcut:

```bash
start-web.bat
```

Manual command:

```bash
cd web
npm install
npm run dev -- --hostname 127.0.0.1
```

Open:

```text
http://127.0.0.1:3000
```

Keep the web terminal open. If Chrome says `127.0.0.1 refused to connect`, the web server is not running.

## 3. Web Test Checklist

Log in as `owner@demo.com` / `password123`.

Use the role account buttons on the login page to test the access-control panels. Expected quick checks:

- `owner@demo.com`: full business menu including settings, imports, reports, audit logs, and AI Assistant.
- `admin@demo.com`: almost full business menu, without billing ownership behavior.
- `manager@demo.com`: operations, forecasts, imports, dead stock, data quality, reports, and AI Assistant.
- `warehouse@demo.com`: product and stock operations only; no profit, purchase, import commit, reports export, or settings.
- `sales@demo.com`: products, stock availability, sales, and customers; no purchase-cost or profit views.
- `purchase@demo.com`: purchases, suppliers, forecasts, reorder suggestions, and draft purchase orders.
- `accountant@demo.com`: sales/purchases/customer outstanding, reports export, profit insight, and audit logs.
- `viewer@demo.com`: read-only dashboard/product/stock/report-style views.
- `auditor@demo.com`: broad read-only audit/report view.
- `ravi@demo.com`: dealer portal only.

Test these pages:

1. Dashboard
   - Confirm summary cards show stock value, monthly sales, gross profit, low stock, dead stock, and receivables.
   - Check `Today's Actions`.
   - Check `Why Profit Changed`.

2. Tally Hub
   - Open `/integrations/tally`.
   - Confirm import status, data quality score, duplicate count, missing mappings, import errors, and insight counts are populated.

3. Data Quality
   - Open `/data-quality`.
   - Confirm duplicate Maggi-style products are listed.
   - Click `Apply` on a missing-field suggestion.
   - Try `Merge into canonical` on a duplicate group.

4. Products
   - Create a new product.
   - Search for it.
   - Look up existing demo products such as `Maggi`, `Parle-G`, and `Surf Excel`.

5. Stock
   - Create a positive stock adjustment with a reason.
   - Create a negative stock adjustment with a reason.
   - Confirm current stock changes.

6. Purchases
   - Create a purchase invoice for an existing supplier/product.
   - Confirm stock increases on the Stock page.

7. Sales
   - Create a sales invoice for an existing customer/product.
   - Confirm stock decreases on the Stock page.

8. Forecasts
   - Click `Run forecast`.
   - Confirm reorder suggestions show current stock, daily demand, expected stockout, and reason.
   - Click `Create Draft Purchase Order`.

9. Dead Stock
   - Open `/dead-stock`.
   - Confirm blocked capital and suggested actions.
   - Change an action from the dropdown.

10. Imports
   - Open `/imports`.
   - Upload one sample file from:
     `backend/src/main/resources/sample-imports/`
   - Try `sample-products.csv`, `sample-sales.csv`, or `sample-tally-stock-items.xml`.
   - Apply mapping, validate, preview, and commit.

11. AI Assistant
   - Ask: `Why did my profit drop this month?`
   - Ask: `What should I reorder this week?`
   - Ask: `Which imported products need cleanup?`
   - Confirm answers reference actual data, not static text.

12. Dealer Portal
   - Sign out.
   - Log in as `ravi@demo.com` / `password123`.
   - Open `/portal`.
   - Add products to cart and place a sales order.
   - Confirm outstanding, orders, and invoices sections render.

## 4. Start the Mobile App

In a new terminal:

```bash
cd mobile
npm install
```

For Android emulator:

```bash
set EXPO_PUBLIC_API_URL=http://10.0.2.2:8080
npm start
```

For Expo Go on a physical phone on the same Wi-Fi:

```bash
set EXPO_PUBLIC_API_URL=http://YOUR_LAPTOP_IP:8080
npm start
```

Find your laptop IP with:

```bash
ipconfig
```

For Expo web preview on the same machine:

```bash
set EXPO_PUBLIC_API_URL=http://127.0.0.1:8080
npm run web
```

## 5. Mobile Test Checklist

Log in as `owner@demo.com` / `password123`.

Test these screens:

1. Dashboard
   - Pull to refresh.
   - Confirm summary numbers load.

2. Product Search
   - Search `Maggi`.
   - Try manual barcode input if camera is unavailable.

3. Stock In
   - Select product and warehouse.
   - Enter quantity and reason.
   - Save.

4. Stock Out
   - Select product and warehouse.
   - Enter quantity and reason.
   - Save.

5. Warehouse Transfer
   - Select product.
   - Choose different source/destination warehouses.
   - Save transfer.

6. Low Stock
   - Confirm low-stock alerts render.

7. Reorder
   - Confirm smart reorder suggestions load from `/api/reorder/suggestions`.

8. Invoice Upload
   - Pick a file.
   - Confirm mock extraction response appears.

9. Settings
   - Confirm API connection details and sign-out flow.

## 6. Common Fixes

- Web-only demo says refused to connect: start `start-web-demo.bat` and keep it open, then open `http://127.0.0.1:3000`.
- Full web app loads but data calls fail: backend is not running on `8080`.
- Mobile emulator cannot call backend: use `http://10.0.2.2:8080`.
- Physical phone cannot call backend: use your laptop LAN IP and allow firewall access.
- Backend demo data not visible: use the explicit local demo override above; the default stack intentionally starts without demo data.
