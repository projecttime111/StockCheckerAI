import fs from "node:fs/promises";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const { Presentation, PresentationFile } = require("@oai/artifact-tool");

const W = 1280;
const H = 720;
const M = 58;

const C = {
  white: "#FFFFFF",
  ink: "#111815",
  muted: "#66716C",
  rule: "#C9D0CC",
  panel: "#F1F3F2",
  green: "#2F6F5E",
  greenDark: "#163F34",
  greenPale: "#E3F1EB",
  amber: "#D59A2B",
  amberPale: "#FFF2D6",
  red: "#B04C46",
  blue: "#3D72A4",
};

const FONT = "Arial";

function addShape(slide, {
  geometry = "rect",
  name,
  left,
  top,
  width,
  height,
  fill = "none",
  lineFill = "none",
  lineWidth = 0,
  radius,
  shadow,
}) {
  const shape = slide.shapes.add({
    geometry,
    name,
    position: { left, top, width, height },
    fill,
    line: { style: "solid", fill: lineFill, width: lineWidth },
    ...(radius !== undefined ? { borderRadius: radius } : {}),
    ...(shadow ? { shadow } : {}),
  });
  return shape;
}

function addText(slide, text, {
  name,
  left,
  top,
  width,
  height,
  fontSize = 22,
  color = C.ink,
  bold = false,
  align = "left",
  valign = "top",
  fill = "none",
  lineFill = "none",
  lineWidth = 0,
  radius,
  inset = 0,
}) {
  const box = addShape(slide, {
    geometry: "textbox",
    name,
    left,
    top,
    width,
    height,
    fill,
    lineFill,
    lineWidth,
    radius,
  });
  box.text = text;
  box.text.style = {
    fontSize,
    typeface: FONT,
    color,
    bold,
    alignment: align,
    verticalAlignment: valign,
    autoFit: "none",
    insets: { top: inset, right: inset, bottom: inset, left: inset },
  };
  return box;
}

function addLine(slide, x1, y1, x2, y2, color = C.rule, width = 1) {
  return slide.shapes.add({
    geometry: "line",
    position: { left: x1, top: y1, width: x2 - x1, height: y2 - y1 },
    fill: "none",
    line: { style: "solid", fill: color, width },
  });
}

function addBulletRows(slide, items, {
  left,
  top,
  width,
  rowHeight = 44,
  fontSize = 21,
  color = C.ink,
  markerColor = C.green,
  markerSize = 8,
}) {
  items.forEach((item, index) => {
    const y = top + index * rowHeight;
    addShape(slide, {
      geometry: "rect",
      left,
      top: y + 9,
      width: markerSize,
      height: markerSize,
      fill: markerColor,
    });
    addText(slide, item, {
      left: left + 22,
      top: y,
      width: width - 22,
      height: rowHeight,
      fontSize,
      color,
    });
  });
}

function addHeader(slide, title, page, eyebrow = "STOCKPILOT AI") {
  addText(slide, eyebrow, {
    left: M,
    top: 36,
    width: 350,
    height: 22,
    fontSize: 14,
    bold: true,
    color: C.green,
  });
  addText(slide, title, {
    left: M,
    top: 72,
    width: 1164,
    height: 82,
    fontSize: 48,
    bold: true,
    color: C.ink,
  });
  addLine(slide, M, 145, W - M, 145, C.rule, 1);
  addText(slide, String(page).padStart(2, "0"), {
    left: 1180,
    top: 668,
    width: 42,
    height: 18,
    fontSize: 12,
    color: C.muted,
    align: "right",
  });
}

function addFooterNote(slide, text) {
  addText(slide, text, {
    left: M,
    top: 660,
    width: 1040,
    height: 22,
    fontSize: 13,
    color: C.muted,
  });
}

function setSources(slide, lines) {
  slide.speakerNotes.textFrame.setText([
    "[Sources]",
    ...lines.map((line) => `- ${line}`),
    "[/Sources]",
  ]);
  slide.speakerNotes.setVisible(true);
}

async function writeBlob(filePath, blob) {
  await fs.writeFile(filePath, new Uint8Array(await blob.arrayBuffer()));
}

function addStep(slide, number, title, detail, left, top, width, highlighted = false) {
  const shape = addShape(slide, {
    geometry: "rect",
    left,
    top,
    width,
    height: 162,
    fill: highlighted ? C.greenPale : C.panel,
    lineFill: highlighted ? C.green : C.rule,
    lineWidth: highlighted ? 2 : 1,
  });
  addText(slide, number, {
    left: left + 14,
    top: top + 12,
    width: 35,
    height: 24,
    fontSize: 16,
    bold: true,
    color: highlighted ? C.green : C.muted,
  });
  addText(slide, title, {
    left: left + 14,
    top: top + 48,
    width: width - 28,
    height: 42,
    fontSize: 21,
    bold: true,
  });
  addText(slide, detail, {
    left: left + 14,
    top: top + 96,
    width: width - 28,
    height: 52,
    fontSize: 15,
    color: C.muted,
  });
  return shape;
}

async function main() {
  const here = path.dirname(fileURLToPath(import.meta.url));
  const docsDir = path.resolve(here, "..");
  const outputDir = path.join(here, "rendered");
  await fs.mkdir(outputDir, { recursive: true });

  const presentation = Presentation.create({
    slideSize: { width: W, height: H },
  });

  // Slide 1: cover.
  {
    const slide = presentation.slides.add();
    slide.background.fill = C.white;

    const heroBytes = await fs.readFile(path.join(here, "warehouse-hero.png"));
    slide.images.add({
      blob: new Uint8Array(heroBytes),
      contentType: "image/png",
      alt: "Warehouse staff using mobile inventory tools in an Indian distribution warehouse",
      fit: "cover",
      position: { left: 640, top: 0, width: 640, height: 720 },
    });

    addShape(slide, {
      geometry: "rect",
      left: 0,
      top: 0,
      width: 640,
      height: 720,
      fill: C.white,
    });
    addShape(slide, {
      geometry: "rect",
      left: 58,
      top: 72,
      width: 8,
      height: 570,
      fill: C.green,
    });
    addText(slide, "STOCKPILOT AI", {
      left: 92,
      top: 78,
      width: 380,
      height: 28,
      fontSize: 16,
      bold: true,
      color: C.green,
    });
    addText(slide, "Inventory decisions\nfrom systems you\nalready use", {
      left: 92,
      top: 154,
      width: 500,
      height: 222,
      fontSize: 56,
      bold: true,
      color: C.ink,
    });
    addText(slide, "Connect Tally or Excel. Predict stockouts.\nReduce dead stock. Improve profit.", {
      left: 92,
      top: 420,
      width: 490,
      height: 82,
      fontSize: 24,
      color: C.muted,
    });
    addText(slide, "MVP, roadmap, and pilot opportunity  |  July 2026", {
      left: 92,
      top: 592,
      width: 450,
      height: 28,
      fontSize: 14,
      color: C.muted,
    });
    addText(slide, "Built for retailers, distributors,\nand multi-warehouse teams", {
      left: 886,
      top: 592,
      width: 340,
      height: 84,
      fontSize: 22,
      color: C.white,
      bold: true,
      fill: C.greenDark,
      inset: 18,
    });
    setSources(slide, [
      "README.md",
      "docs/STOCKPILOT_AI_ROADMAP_AND_PITCH_NOTES.md",
      "Generated cover image: docs/pitch-assets/warehouse-hero.png",
    ]);
  }

  // Slide 2: problem.
  {
    const slide = presentation.slides.add();
    slide.background.fill = C.white;
    addHeader(slide, "Businesses have data, but daily decisions are still manual", 2);

    addText(slide, "Data exists.\nDecisions still\nhappen by instinct.", {
      left: M,
      top: 195,
      width: 460,
      height: 250,
      fontSize: 52,
      bold: true,
    });
    addLine(slide, 555, 190, 555, 520, C.rule, 1);

    addBulletRows(slide, [
      "Stockouts are discovered after a sale is lost.",
      "Slow products keep working capital on the shelf.",
      "Reorder quantities depend on memory and spreadsheets.",
      "Receivable follow-up is scattered across calls and notes.",
      "Messy Tally exports weaken every downstream report.",
    ], {
      left: 600,
      top: 205,
      width: 590,
      rowHeight: 58,
      fontSize: 22,
    });

    addText(slide, "Accounting explains what happened. StockPilot focuses the team on what to do next.", {
      left: M,
      top: 566,
      width: 1164,
      height: 72,
      fontSize: 28,
      bold: true,
      color: C.greenDark,
      fill: C.greenPale,
      inset: 18,
    });
    addFooterNote(slide, "The starting point is existing Tally, Excel, and ERP data, not a replacement accounting system.");
    setSources(slide, [
      "docs/STOCKPILOT_AI_PITCH.md",
      "docs/STOCKPILOT_AI_ROADMAP_AND_PITCH_NOTES.md",
    ]);
  }

  // Slide 3: product flow.
  {
    const slide = presentation.slides.add();
    slide.background.fill = C.white;
    addHeader(slide, "StockPilot turns exported records into controlled next actions", 3);

    const steps = [
      ["01", "Connect", "Tally, Excel, CSV, XML, JSON"],
      ["02", "Classify", "Detect file purpose and dates"],
      ["03", "Stage", "Keep raw and canonical rows"],
      ["04", "Validate", "Match, dry run, and review"],
      ["05", "Verify", "Build trusted ledgers"],
      ["06", "Predict", "Forecast stock and cash risk"],
      ["07", "Approve", "Human confirmation for risk"],
      ["08", "Act", "Reorder, transfer, collect"],
    ];
    const boxW = 132;
    const gap = 14;
    const startX = 58;
    const boxes = [];
    steps.forEach((step, index) => {
      boxes.push(addStep(
        slide,
        step[0],
        step[1],
        step[2],
        startX + index * (boxW + gap),
        230,
        boxW,
        index === 5 || index === 7,
      ));
    });
    for (let index = 0; index < boxes.length - 1; index += 1) {
      slide.shapes.connect(boxes[index], boxes[index + 1], {
        kind: "straight",
        fromSide: "right",
        toSide: "left",
        line: { style: "solid", fill: C.muted, width: 1.5 },
        head: { type: "arrow", width: "sm", length: "sm" },
      });
    }
    addText(slide, "The advantage is not a prettier dashboard. It is an auditable path from imperfect data to an approved business action.", {
      left: 120,
      top: 466,
      width: 1040,
      height: 92,
      fontSize: 30,
      bold: true,
      align: "center",
      color: C.greenDark,
    });
    addFooterNote(slide, "Every step preserves tenant scope, source evidence, and reconciliation.");
    setSources(slide, [
      "README.md, Smart Import Workspace and Stock Ledger sections",
      "docs/IMPORT_HARDENING.md",
    ]);
  }

  // Slide 4: current MVP.
  {
    const slide = presentation.slides.add();
    slide.background.fill = C.white;
    addHeader(slide, "The current repository is a working full-stack MVP", 4);

    const rows = [
      ["Security and tenancy", "JWT, verified email, password reset, optional Google sign-in, roles, permissions, audit"],
      ["Inventory core", "Products, parties, warehouses, purchases, sales, transfers, adjustments, ledger-derived stock"],
      ["Smart Import", "Multi-file sessions, classification, staging, dry run, safe commit, snapshot delta, reconciliation"],
      ["Intelligence", "Dashboard, forecasts, reorder, dead stock, data quality, profit-change and AI answers"],
      ["Action surfaces", "Responsive web, Expo mobile, dealer portal, payments, reports, audit viewer"],
    ];
    const rowTop = 172;
    const rowH = 88;
    rows.forEach((row, index) => {
      const y = rowTop + index * rowH;
      addShape(slide, {
        geometry: "rect",
        left: M,
        top: y,
        width: 12,
        height: 64,
        fill: index < 3 ? C.green : C.ink,
      });
      addText(slide, row[0], {
        left: 86,
        top: y,
        width: 265,
        height: 64,
        fontSize: 23,
        bold: true,
        valign: "middle",
      });
      addText(slide, row[1], {
        left: 372,
        top: y,
        width: 850,
        height: 64,
        fontSize: 20,
        color: C.muted,
        valign: "middle",
      });
      if (index < rows.length - 1) {
        addLine(slide, 86, y + 73, 1222, y + 73, C.rule, 1);
      }
    });
    addFooterNote(slide, "Repository status as of main @ de71184. Working MVP does not mean production certification.");
    setSources(slide, [
      "README.md",
      "backend/src/main/resources/db/migration/V1__init_schema.sql through V17__cashbook_manual_resolution.sql",
      "web/app and mobile/app route inventories",
    ]);
  }

  // Slide 5: Smart Import.
  {
    const slide = presentation.slides.add();
    slide.background.fill = C.white;
    addHeader(slide, "Smart Import removes file-order confusion without weakening controls", 5);

    const flowLeft = 58;
    const flowW = 720;
    const nodes = [
      ["Upload together", "Stock, masters, vouchers, ageing, and cashbook files can arrive in any order."],
      ["Classify and stage", "Content signatures create a dependency-aware plan and canonical review rows."],
      ["Dry run and review", "Exact matches are reused; uncertain matches and invalid rows remain visible."],
      ["Commit and reconcile", "Transactional, idempotent posting with counts, audit events, retry, and undo."],
    ];
    const nodeShapes = [];
    nodes.forEach((node, index) => {
      const y = 176 + index * 104;
      const shape = addShape(slide, {
        geometry: "rect",
        left: flowLeft,
        top: y,
        width: flowW,
        height: 80,
        fill: index === 3 ? C.greenPale : C.panel,
        lineFill: index === 3 ? C.green : C.rule,
        lineWidth: index === 3 ? 2 : 1,
      });
      nodeShapes.push(shape);
      addText(slide, String(index + 1).padStart(2, "0"), {
        left: flowLeft + 18,
        top: y + 18,
        width: 42,
        height: 30,
        fontSize: 16,
        bold: true,
        color: C.green,
      });
      addText(slide, node[0], {
        left: flowLeft + 75,
        top: y + 13,
        width: 205,
        height: 36,
        fontSize: 22,
        bold: true,
      });
      addText(slide, node[1], {
        left: flowLeft + 288,
        top: y + 12,
        width: 410,
        height: 58,
        fontSize: 17,
        color: C.muted,
      });
    });
    for (let index = 0; index < nodeShapes.length - 1; index += 1) {
      slide.shapes.connect(nodeShapes[index], nodeShapes[index + 1], {
        kind: "straight",
        fromSide: "bottom",
        toSide: "top",
        line: { style: "solid", fill: C.muted, width: 1.5 },
        head: { type: "arrow", width: "sm", length: "sm" },
      });
    }

    addText(slide, "Safety built in", {
      left: 845,
      top: 178,
      width: 350,
      height: 42,
      fontSize: 30,
      bold: true,
    });
    addBulletRows(slide, [
      "Closing stock uses snapshot delta logic.",
      "Repeated files do not double stock.",
      "Historical vouchers can post invoice-only.",
      "Unmatched cashbook rows stay in review.",
      "Fingerprints prevent duplicate payments.",
      "Every query and write is tenant-scoped.",
    ], {
      left: 850,
      top: 242,
      width: 360,
      rowHeight: 52,
      fontSize: 18,
    });
    addFooterNote(slide, "This is a controlled ingestion workspace, not a blind XML-to-database loader.");
    setSources(slide, [
      "README.md, Smart Import Workspace section",
      "docs/IMPORT_HARDENING.md",
      "docs/REAL_TALLY_IMPORT_TESTING.md",
    ]);
  }

  // Slide 6: insight to action.
  {
    const slide = presentation.slides.add();
    slide.background.fill = C.white;
    addHeader(slide, "The MVP closes the loop from visibility to action", 6);

    const columns = [
      ["SEE", "A trusted operating picture", [
        "Current stock and stock value",
        "Monthly sales and gross profit",
        "Receivables and payment status",
        "Import health and data quality",
      ]],
      ["DECIDE", "A prioritized recommendation", [
        "What will stock out soon",
        "How much to reorder and why",
        "Where capital is blocked",
        "Why profit or margin changed",
      ]],
      ["ACT", "A workflow for the right user", [
        "Create a purchase proposal",
        "Transfer or adjust stock",
        "Follow up and receive payment",
        "Review, approve, export, audit",
      ]],
    ];
    const colW = 350;
    const gap = 52;
    columns.forEach((column, index) => {
      const x = M + index * (colW + gap);
      if (index > 0) {
        addLine(slide, x - 26, 190, x - 26, 558, C.rule, 1);
      }
      addText(slide, column[0], {
        left: x,
        top: 185,
        width: colW,
        height: 28,
        fontSize: 15,
        bold: true,
        color: C.green,
      });
      addText(slide, column[1], {
        left: x,
        top: 232,
        width: colW,
        height: 84,
        fontSize: 30,
        bold: true,
      });
      addBulletRows(slide, column[2], {
        left: x,
        top: 340,
        width: colW,
        rowHeight: 50,
        fontSize: 18,
        markerColor: index === 2 ? C.green : C.ink,
      });
    });

    addText(slide, "Owner  |  Manager  |  Warehouse  |  Sales  |  Purchase  |  Accountant  |  Dealer", {
      left: 110,
      top: 594,
      width: 1060,
      height: 40,
      fontSize: 19,
      bold: true,
      align: "center",
      color: C.greenDark,
    });
    addFooterNote(slide, "The same tenant data powers the web workspace, mobile operations, and dealer portal.");
    setSources(slide, [
      "README.md, Access Control Model",
      "docs/RBAC_COMPARISON.md",
    ]);
  }

  // Slide 7: positioning.
  {
    const slide = presentation.slides.add();
    slide.background.fill = C.white;
    addHeader(slide, "StockPilot complements Tally instead of rebuilding it", 7);

    const cols = [
      {
        x: 58,
        title: "Tally / ERP",
        subtitle: "System of record",
        items: ["Accounting and GST", "Ledgers and vouchers", "Invoices and compliance", "Historical reporting"],
        fill: C.panel,
      },
      {
        x: 442,
        title: "BI / Reporting",
        subtitle: "System of visibility",
        items: ["Dashboards", "Charts and filters", "Ad hoc questions", "Historical patterns"],
        fill: C.panel,
      },
      {
        x: 826,
        title: "StockPilot AI",
        subtitle: "System of decision and action",
        items: ["Forecast and reorder", "Dead stock and margin", "Cash and data quality", "Approve, act, and measure"],
        fill: C.greenPale,
      },
    ];
    cols.forEach((col, index) => {
      addShape(slide, {
        geometry: "rect",
        left: col.x,
        top: 188,
        width: 338,
        height: 350,
        fill: col.fill,
        lineFill: index === 2 ? C.green : C.rule,
        lineWidth: index === 2 ? 2 : 1,
      });
      addText(slide, col.title, {
        left: col.x + 24,
        top: 218,
        width: 290,
        height: 44,
        fontSize: 30,
        bold: true,
      });
      addText(slide, col.subtitle, {
        left: col.x + 24,
        top: 272,
        width: 290,
        height: 30,
        fontSize: 17,
        bold: true,
        color: index === 2 ? C.green : C.muted,
      });
      addBulletRows(slide, col.items, {
        left: col.x + 24,
        top: 332,
        width: 290,
        rowHeight: 44,
        fontSize: 18,
        markerColor: index === 2 ? C.green : C.ink,
      });
    });
    addText(slide, "Designed to work with Tally, not replace it.", {
      left: 170,
      top: 575,
      width: 940,
      height: 54,
      fontSize: 34,
      bold: true,
      align: "center",
      color: C.greenDark,
    });
    addFooterNote(slide, "The product moat should be trusted ingestion, explainable decisions, and action workflows.");
    setSources(slide, [
      "README.md, How StockPilot AI is different from Tally",
      "https://www.talligence.in/",
      "https://kolossusai.in/for-tally-users/",
      "https://fireai.in/integrations/tally",
      "https://www.easyreports.in/",
    ]);
  }

  // Slide 8: roadmap.
  {
    const slide = presentation.slides.add();
    slide.background.fill = C.white;
    addHeader(slide, "The next release is about trust, automation, and pilot readiness", 8);

    const roadmap = [
      {
        x: 58,
        label: "NOW",
        title: "Working MVP",
        color: C.green,
        items: [
          "Stock ledger and dashboards",
          "Smart Import Phase 4B-2B",
          "Forecast, dead stock, profit",
          "Web, mobile, portal, payments",
        ],
      },
      {
        x: 448,
        label: "NEXT",
        title: "Pilot ready",
        color: C.ink,
        items: [
          "Metric evidence and lineage",
          "Read-only Tally sync",
          "Invites, scoped roles, approvals",
          "Alerts and forecast backtesting",
        ],
      },
      {
        x: 838,
        label: "LATER",
        title: "Scale",
        color: C.muted,
        items: [
          "Multi-company command center",
          "Cross-warehouse optimization",
          "Supplier collaboration",
          "Connector SDK and outcomes",
        ],
      },
    ];
    addLine(slide, 92, 236, 1182, 236, C.rule, 4);
    roadmap.forEach((stage, index) => {
      addShape(slide, {
        geometry: "ellipse",
        left: stage.x,
        top: 216,
        width: 40,
        height: 40,
        fill: stage.color,
        lineFill: C.white,
        lineWidth: 4,
      });
      addText(slide, stage.label, {
        left: stage.x,
        top: 170,
        width: 120,
        height: 24,
        fontSize: 14,
        bold: true,
        color: stage.color,
      });
      addText(slide, stage.title, {
        left: stage.x,
        top: 286,
        width: 330,
        height: 46,
        fontSize: 30,
        bold: true,
      });
      addBulletRows(slide, stage.items, {
        left: stage.x,
        top: 356,
        width: 330,
        rowHeight: 48,
        fontSize: 18,
        markerColor: stage.color,
      });
      if (index < 2) {
        addLine(slide, stage.x + 350, 290, stage.x + 350, 568, C.rule, 1);
      }
    });
    addFooterNote(slide, "Keep trading and distribution as the commercial focus until the pilot proves repeatable value.");
    setSources(slide, [
      "docs/STOCKPILOT_AI_ROADMAP_AND_PITCH_NOTES.md",
      "docs/FEATURE_STATUS.md",
      "docs/SECURITY.md",
    ]);
  }

  // Slide 9: architecture and capability packs.
  {
    const slide = presentation.slides.add();
    slide.background.fill = C.white;
    addHeader(slide, "One core platform can support several capability packs", 9);

    const core = addShape(slide, {
      geometry: "rect",
      left: 242,
      top: 176,
      width: 796,
      height: 142,
      fill: C.greenDark,
      lineFill: C.greenDark,
      lineWidth: 1,
    });
    addText(slide, "STOCKPILOT CORE", {
      left: 276,
      top: 200,
      width: 300,
      height: 36,
      fontSize: 27,
      bold: true,
      color: C.white,
    });
    addText(slide, "Tenancy and permissions  |  Connectors and staging  |  Canonical ledgers\nEvidence and metrics  |  AI and forecasts  |  Alerts, approvals, web and mobile", {
      left: 276,
      top: 246,
      width: 720,
      height: 58,
      fontSize: 17,
      color: C.white,
    });

    const packs = [
      ["Trading and\nDistribution", "Commercial focus", C.greenPale, C.green],
      ["Manufacturing", "BOM, WIP, yield", C.panel, C.rule],
      ["Project\nInventory", "Sites, budgets, cost", C.panel, C.rule],
      ["CRM\nIntelligence", "Customer and API data", C.panel, C.rule],
    ];
    const packShapes = [];
    packs.forEach((pack, index) => {
      const x = 58 + index * 306;
      const shape = addShape(slide, {
        geometry: "rect",
        left: x,
        top: 430,
        width: 258,
        height: 150,
        fill: pack[2],
        lineFill: pack[3],
        lineWidth: index === 0 ? 2 : 1,
      });
      packShapes.push(shape);
      addText(slide, pack[0], {
        left: x + 20,
        top: 458,
        width: 218,
        height: 66,
        fontSize: 26,
        bold: true,
        align: "center",
      });
      addText(slide, pack[1], {
        left: x + 20,
        top: 538,
        width: 218,
        height: 26,
        fontSize: 15,
        color: index === 0 ? C.green : C.muted,
        bold: true,
        align: "center",
      });
      slide.shapes.connect(core, shape, {
        kind: "elbow",
        fromSide: "bottom",
        toSide: "top",
        line: { style: "solid", fill: index === 0 ? C.green : C.rule, width: index === 0 ? 2.5 : 1.5 },
        head: { type: "arrow", width: "sm", length: "sm" },
      });
    });
    addText(slide, "Industry depth should be added as controlled packs, not separate products or duplicate codebases.", {
      left: 155,
      top: 610,
      width: 970,
      height: 42,
      fontSize: 22,
      bold: true,
      align: "center",
      color: C.greenDark,
    });
    setSources(slide, [
      "docs/STOCKPILOT_AI_ROADMAP_AND_PITCH_NOTES.md",
      "https://kolossusai.in/for-trading/",
      "https://kolossusai.in/for-manufacturing/",
      "https://kolossusai.in/for-real-estate/",
      "https://kolossusai.in/for-custom-crms/",
    ]);
  }

  // Slide 10: disciplined expansion.
  {
    const slide = presentation.slides.add();
    slide.background.fill = C.white;
    addHeader(slide, "Earn the right to expand, one operational problem at a time", 10);

    const stages = [
      { x: 58, y: 468, w: 270, h: 120, n: "01", t: "Trading and distribution", d: "Inventory, purchase, cash, dealer workflows", fill: C.green },
      { x: 340, y: 388, w: 270, h: 200, n: "02", t: "Multi-company operations", d: "Command center, stock balancing, supplier collaboration", fill: C.greenDark },
      { x: 622, y: 296, w: 270, h: 292, n: "03", t: "Manufacturing", d: "BOM, planning, WIP, yield, batch traceability", fill: C.ink },
      { x: 904, y: 204, w: 318, h: 384, n: "04", t: "Project and CRM intelligence", d: "Site inventory, project cost, custom data connectors", fill: C.muted },
    ];
    stages.forEach((stage) => {
      addShape(slide, {
        geometry: "rect",
        left: stage.x,
        top: stage.y,
        width: stage.w,
        height: stage.h,
        fill: stage.fill,
      });
      addText(slide, stage.n, {
        left: stage.x + 20,
        top: stage.y + 18,
        width: 40,
        height: 26,
        fontSize: 14,
        bold: true,
        color: C.white,
      });
      addText(slide, stage.t, {
        left: stage.x + 20,
        top: stage.y + 52,
        width: stage.w - 40,
        height: 74,
        fontSize: 24,
        bold: true,
        color: C.white,
      });
      if (stage.h >= 190) {
        addText(slide, stage.d, {
          left: stage.x + 20,
          top: stage.y + 142,
          width: stage.w - 40,
          height: 100,
          fontSize: 17,
          color: C.white,
        });
      }
    });
    addText(slide, "Do not build GST filing, a full ERP replacement, unrestricted text-to-SQL, or uncontrolled write-back.", {
      left: 58,
      top: 617,
      width: 1164,
      height: 34,
      fontSize: 18,
      bold: true,
      color: C.red,
      align: "center",
    });
    setSources(slide, [
      "docs/STOCKPILOT_AI_ROADMAP_AND_PITCH_NOTES.md",
    ]);
  }

  // Slide 11: pilot.
  {
    const slide = presentation.slides.add();
    slide.background.fill = C.white;
    addHeader(slide, "A focused pilot can prove value without overpromising", 11);

    addText(slide, "Ideal pilot partner", {
      left: 58,
      top: 188,
      width: 480,
      height: 42,
      fontSize: 31,
      bold: true,
    });
    addBulletRows(slide, [
      "Distributor or wholesaler using Tally and Excel",
      "One or more active warehouses",
      "At least 12 months of transaction history",
      "Visible stockout, dead-stock, or collection pain",
      "Weekly owner review of recommendations",
    ], {
      left: 62,
      top: 258,
      width: 500,
      rowHeight: 54,
      fontSize: 19,
    });

    addLine(slide, 610, 184, 610, 575, C.rule, 1);
    addText(slide, "Measure the outcome", {
      left: 670,
      top: 188,
      width: 520,
      height: 42,
      fontSize: 31,
      bold: true,
    });
    addBulletRows(slide, [
      "Stockout days and lost-sales signals",
      "Dead-stock value and inventory turns",
      "Receivable follow-up time",
      "Import accuracy and time saved",
      "Recommendation acceptance",
      "Action result after 7, 30, and 90 days",
    ], {
      left: 674,
      top: 258,
      width: 510,
      rowHeight: 48,
      fontSize: 19,
      markerColor: C.blue,
    });

    addText(slide, "Pilot ask: 3 to 5 businesses, anonymized exports, and a weekly outcome review.", {
      left: 128,
      top: 590,
      width: 1024,
      height: 58,
      fontSize: 28,
      bold: true,
      align: "center",
      color: C.greenDark,
      fill: C.greenPale,
      inset: 12,
    });
    addFooterNote(slide, "Financial impact should be measured in the pilot, not invented in the pitch.");
    setSources(slide, [
      "docs/STOCKPILOT_AI_ROADMAP_AND_PITCH_NOTES.md",
    ]);
  }

  // Slide 12: close.
  {
    const slide = presentation.slides.add();
    slide.background.fill = C.white;
    addShape(slide, {
      geometry: "rect",
      left: 58,
      top: 62,
      width: 8,
      height: 590,
      fill: C.green,
    });
    addText(slide, "STOCKPILOT AI", {
      left: 96,
      top: 74,
      width: 340,
      height: 26,
      fontSize: 16,
      bold: true,
      color: C.green,
    });
    addText(slide, "Make every stock\nand cash decision\neasier to trust.", {
      left: 96,
      top: 148,
      width: 720,
      height: 260,
      fontSize: 70,
      bold: true,
    });
    addText(slide, "The vision is simple: turn imperfect operational data into an explainable next action for the right person.", {
      left: 96,
      top: 452,
      width: 720,
      height: 90,
      fontSize: 27,
      color: C.muted,
    });

    addShape(slide, {
      geometry: "rect",
      left: 878,
      top: 126,
      width: 344,
      height: 420,
      fill: C.greenDark,
    });
    addText(slide, "NEXT CONVERSATION", {
      left: 906,
      top: 164,
      width: 290,
      height: 28,
      fontSize: 14,
      bold: true,
      color: C.white,
    });
    addText(slide, "Pilot partners", {
      left: 906,
      top: 238,
      width: 285,
      height: 42,
      fontSize: 28,
      bold: true,
      color: C.white,
    });
    addLine(slide, 906, 300, 1194, 300, "#6F978B", 1);
    addText(slide, "Anonymized Tally exports", {
      left: 906,
      top: 330,
      width: 285,
      height: 62,
      fontSize: 28,
      bold: true,
      color: C.white,
    });
    addLine(slide, 906, 414, 1194, 414, "#6F978B", 1);
    addText(slide, "Weekly outcome reviews", {
      left: 906,
      top: 444,
      width: 285,
      height: 62,
      fontSize: 28,
      bold: true,
      color: C.white,
    });
    addText(slide, "Connect Tally or Excel. Predict stockouts. Reduce dead stock. Improve profit.", {
      left: 96,
      top: 614,
      width: 1080,
      height: 30,
      fontSize: 18,
      bold: true,
      color: C.green,
    });
    setSources(slide, [
      "docs/STOCKPILOT_AI_ROADMAP_AND_PITCH_NOTES.md",
    ]);
  }

  for (const [index, slide] of presentation.slides.items.entries()) {
    const stem = `slide-${String(index + 1).padStart(2, "0")}`;
    await writeBlob(
      path.join(outputDir, `${stem}.png`),
      await presentation.export({ slide, format: "png", scale: 1 }),
    );
    const layout = await slide.export({ format: "layout" });
    await fs.writeFile(path.join(outputDir, `${stem}.layout.json`), await layout.text());
  }

  await writeBlob(
    path.join(outputDir, "stockpilot-pitch-montage.webp"),
    await presentation.export({ format: "webp", montage: true, scale: 1 }),
  );

  const pptx = await PresentationFile.exportPptx(presentation);
  await pptx.save(path.join(docsDir, "StockPilot_AI_Pitch_Deck.pptx"));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
