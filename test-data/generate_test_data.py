"""
Generates the synthetic test-data set required by Section 6 of the assignment:
all content here is fabricated (no real patient/client data). Produces PDF
attachments under test-data/pdfs/ and email fixtures under test-data/emails/
(consumed by LocalBatchIngestionService / POST /api/batch/run).

Run: python3 generate_test_data.py   (from within test-data/, using the
ai-service venv which already has reportlab + Pillow installed)
"""
import json
import os

from reportlab.lib import colors
from reportlab.lib.pagesizes import LETTER
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image as RLImage
from reportlab.pdfgen import canvas as rl_canvas
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
PDF_DIR = os.path.join(HERE, "pdfs")
EMAIL_DIR = os.path.join(HERE, "emails")
FONT_PATH = os.path.join(HERE, "fonts", "Caveat.ttf")

os.makedirs(PDF_DIR, exist_ok=True)
os.makedirs(EMAIL_DIR, exist_ok=True)

styles = getSampleStyleSheet()
LABEL_STYLE = ParagraphStyle("label", parent=styles["Normal"], fontName="Helvetica-Bold", spaceAfter=2)
VALUE_STYLE = ParagraphStyle("value", parent=styles["Normal"], spaceAfter=10)
H1 = ParagraphStyle("h1", parent=styles["Heading1"], fontSize=15)
H2 = ParagraphStyle("h2", parent=styles["Heading2"], fontSize=12)
BODY = ParagraphStyle("body", parent=styles["Normal"], fontSize=10, leading=14, spaceAfter=8)


def make_digital_form_pdf(filename, title, fields, table=None, image_path=None, image_caption=None):
    """A plain filled-in report form: 'Label: Value' pairs, an optional table, an optional embedded image."""
    path = os.path.join(PDF_DIR, filename)
    doc = SimpleDocTemplate(path, pagesize=LETTER, topMargin=0.75 * inch, bottomMargin=0.75 * inch)
    story = [Paragraph(title, H1), Spacer(1, 12)]
    for section, rows in fields.items():
        story.append(Paragraph(section, H2))
        for label, value in rows.items():
            story.append(Paragraph(f"<b>{label}:</b> {value}", VALUE_STYLE))
        story.append(Spacer(1, 6))
    if table:
        headers, rows = table
        data = [headers] + rows
        t = Table(data, hAlign="LEFT")
        t.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#dde3ea")),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
            ("FONTSIZE", (0, 0), (-1, -1), 9),
            ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ]))
        story.append(Spacer(1, 6))
        story.append(t)
    if image_path:
        story.append(Spacer(1, 12))
        if image_caption:
            story.append(Paragraph(image_caption, VALUE_STYLE))
        story.append(RLImage(image_path, width=2.2 * inch, height=2.2 * inch))
    doc.build(story)
    return filename


def make_article_pdf(filename, title, authors, journal_line, abstract, case_report, discussion, references, out_dir=None):
    out_dir = out_dir or PDF_DIR
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, filename)
    doc = SimpleDocTemplate(path, pagesize=LETTER, topMargin=0.75 * inch, bottomMargin=0.75 * inch)
    story = [
        Paragraph(title, H1),
        Paragraph(authors, ParagraphStyle("authors", parent=BODY, fontName="Helvetica-Oblique")),
        Paragraph(journal_line, ParagraphStyle("journal", parent=BODY, fontSize=9, textColor=colors.grey)),
        Spacer(1, 10),
        Paragraph("Abstract", H2),
        Paragraph(abstract, BODY),
        Paragraph("Introduction", H2),
        Paragraph(
            "Adverse drug reactions remain an important area of post-marketing surveillance. "
            "We describe a case observed in clinical practice and discuss its implications.", BODY),
        Paragraph("Case Report", H2),
        Paragraph(case_report, BODY),
        Paragraph("Discussion", H2),
        Paragraph(discussion, BODY),
        Paragraph("References", H2),
    ]
    for i, ref in enumerate(references, 1):
        story.append(Paragraph(f"{i}. {ref}", ParagraphStyle("ref", parent=BODY, fontSize=8)))
    doc.build(story)
    return filename


def make_handwritten_pdf(filename, lines, title="Adverse Event Intake Form (hand-completed)"):
    """Renders text with a handwriting-style font onto an image with NO PDF text layer,
    so the flavor-detection heuristic correctly classifies it as SCANNED_HANDWRITTEN."""
    img = Image.new("RGB", (1650, 2200), "white")
    draw = ImageDraw.Draw(img)
    title_font = ImageFont.truetype(FONT_PATH, 54)
    line_font = ImageFont.truetype(FONT_PATH, 40)
    draw.text((80, 60), title, font=title_font, fill="black")
    draw.line([(80, 140), (1570, 140)], fill="black", width=2)
    y = 200
    for line in lines:
        draw.text((100, y), line, font=line_font, fill=(20, 20, 60))
        y += 90
    png_path = os.path.join(PDF_DIR, filename.replace(".pdf", ".png"))
    img.save(png_path)

    path = os.path.join(PDF_DIR, filename)
    c = rl_canvas.Canvas(path, pagesize=(1650 * 0.5, 2200 * 0.5))
    c.drawImage(png_path, 0, 0, width=1650 * 0.5, height=2200 * 0.5)
    c.showPage()
    c.save()
    os.remove(png_path)
    return filename


def make_damaged_product_image():
    """A simple synthetic 'photo' of a damaged product (drawn, not a real photo)."""
    img = Image.new("RGB", (500, 500), (250, 250, 245))
    d = ImageDraw.Draw(img)
    d.rectangle([150, 80, 350, 420], outline=(60, 60, 60), width=4, fill=(235, 235, 225))
    d.rectangle([180, 40, 320, 90], outline=(60, 60, 60), width=4, fill=(200, 200, 190))
    d.line([150, 95, 350, 95], fill=(200, 30, 30), width=6)
    d.text((190, 200), "SEAL\nBROKEN", fill=(180, 20, 20))
    path = os.path.join(PDF_DIR, "_damaged_seal.png")
    img.save(path)
    return path


def write_email(name, sender, subject, received_at, body, attachments=None, note=""):
    fixture = {
        "sender": sender,
        "subject": subject,
        "receivedAt": received_at,
        "bodyText": body,
        "attachments": [f"../pdfs/{a}" for a in (attachments or [])],
        "note": note,
    }
    with open(os.path.join(EMAIL_DIR, f"{name}.json"), "w") as f:
        json.dump(fixture, f, indent=2)


# ---------------------------------------------------------------------------
# 1. ICSR emails with varying detail (6)
# ---------------------------------------------------------------------------

make_digital_form_pdf(
    "icsr-01-form.pdf",
    "Adverse Event Report Form",
    {
        "Patient": {"Age": "54", "Sex": "Female", "Weight/Height": "68 kg / 165 cm",
                     "Relevant history": "Hypertension, no known drug allergies"},
        "Reporter": {"Name": "Dr. Priya Nair", "Role": "Treating physician", "Country": "India"},
        "Product": {"Name": "Neptazol", "Dose": "20 mg once daily", "Route": "Oral",
                     "Start date": "2026-07-01", "Stop date": "2026-07-10"},
        "Reaction": {"Description": "Severe facial swelling and widespread rash",
                      "Onset": "2026-07-08 (day 8 of treatment)",
                      "Outcome": "Resolved after discontinuation and antihistamine treatment"},
        "Severity": {"Serious": "Yes", "Criteria": "Required emergency room visit and overnight hospital observation"},
    },
    table=(["Timepoint", "Blood Pressure", "Heart Rate", "Temp (C)"],
           [["On admission", "138/86", "104", "37.8"], ["6h later", "126/80", "88", "37.1"]]),
)
write_email(
    "icsr-01", "dr.priya.nair@rivermed-clinic.example",
    "Adverse reaction report - Neptazol 20mg",
    "2026-07-11T09:20:00Z",
    "Dear Safety Team,\n\nPlease find attached the completed adverse event report for a patient who developed "
    "a serious reaction to Neptazol. She required an ER visit. Let me know if you need anything further.\n\n"
    "Regards,\nDr. Priya Nair",
    attachments=["icsr-01-form.pdf"],
    note="ICSR - high detail, digital PDF with table",
)

make_digital_form_pdf(
    "icsr-02-labs.pdf",
    "Laboratory Notes",
    {
        "Patient": {"Age": "34", "Sex": "Female"},
        "Product": {"Name": "Ferbolex", "Dose": "500 mg twice daily"},
        "Reaction": {"Description": "Hives / urticaria across arms and torso",
                      "Onset": "approximately after the second dose"},
    },
    table=(["Test", "Result", "Reference range"],
           [["WBC", "11.2 x10^9/L", "4.0-11.0"], ["Eosinophils", "8.5%", "1-4%"]]),
)
write_email(
    "icsr-02", "nurse.callahan@meadowbrook-clinic.example",
    "Possible reaction to Ferbolex",
    "2026-07-15T14:05:00Z",
    "Hi, a patient of ours developed hives after starting Ferbolex, seems to have started after the second dose. "
    "Attaching the lab notes we have. Not 100% sure on exact timing. She's a 34 year old woman.",
    attachments=["icsr-02-labs.pdf"],
    note="ICSR - moderate detail, digital PDF with small lab table",
)

write_email(
    "icsr-03", "concerned.parent55@mailbox.example",
    "my son had a bad reaction",
    "2026-07-18T22:41:00Z",
    "hi, my son took some medicine (i think it's called Amoxiclav?) and got really sick with a rash all over his "
    "body. it happened pretty quick. not sure exactly when he started it or how much. should we be worried??",
    attachments=[],
    note="ICSR - low/vague detail, no attachment, tests 'Not stated' handling",
)

make_digital_form_pdf(
    "icsr-04-form.pdf",
    "Combined Adverse Event / Product Complaint Report",
    {
        "Patient": {"Age": "29", "Sex": "Male", "Relevant history": "None reported"},
        "Reporter": {"Name": "T. Tomlin, RPh", "Role": "Pharmacist", "Country": "USA"},
        "Product": {"Name": "Cortivex Cream", "Dose": "Applied topically twice daily",
                     "Start date": "2026-07-20", "Stop date": "2026-07-21"},
        "Reaction": {"Description": "Skin inflammation and blistering at application site",
                      "Onset": "within hours of first application", "Outcome": "Ongoing at time of report"},
        "Severity": {"Serious": "Not stated", "Criteria": "Not stated"},
        "Product Defect": {"Batch/Lot number": "B4471",
                             "What's wrong": "Visible discoloration and unusual odor in the cream",
                             "Photo mentioned": "No"},
    },
)
write_email(
    "icsr-04", "pharmacist.tomlin@citycare-pharmacy.example",
    "Contaminated product caused patient reaction - batch B4471",
    "2026-07-21T11:10:00Z",
    "A patient came in after using Cortivex Cream from batch B4471. The tube had a strange smell and "
    "discoloration. After applying it his skin became inflamed and blistered. Full report attached.",
    attachments=["icsr-04-form.pdf"],
    note="ICSR + PQC dual classification, digital PDF",
)

make_handwritten_pdf(
    "icsr-05-handwritten.pdf",
    [
        "Patient: Grace Owens   Age: 71   Sex: F",
        "Drug: Sedaprin 10mg, once at night",
        "Reaction: dizziness, then fell in the",
        "bathroom. Started ~2 days after",
        "starting the drug. Bruised hip, no",
        "fracture on X-ray. Drug stopped.",
        "Reported by: Dr. K. Owusu, GP",
    ],
)
write_email(
    "icsr-05", "dr.owusu@brightpath-medical.example",
    "Scanned adverse event form attached",
    "2026-07-23T16:00:00Z",
    "Please find the scanned handwritten intake form attached for this adverse event - apologies, it was a busy "
    "clinic day and we didn't have time to type it up.",
    attachments=["icsr-05-handwritten.pdf"],
    note="ICSR - scanned/handwritten-style PDF",
)

make_handwritten_pdf(
    "icsr-06-handwritten.pdf",
    [
        "Name: R. Thompson   Age: 45   Sex: M",
        "Medicine: Glucoreg",
        "What happened: felt sick to my stomach",
        "and threw up twice, maybe 1 hr after",
        "taking my morning dose. happened again",
        "next day too. mild, didn't go to doctor.",
        "Reported by: myself (patient)",
    ],
    title="Patient Self-Report Form (hand-completed)",
)
write_email(
    "icsr-06", "r.thompson88@personalmail.example",
    "reporting side effect",
    "2026-07-24T08:30:00Z",
    "Hi, I wanted to report a side effect from my medication. I filled out the attached form by hand about what "
    "happened to me.",
    attachments=["icsr-06-handwritten.pdf"],
    note="ICSR - scanned/handwritten-style PDF (patient self-report)",
)

# ---------------------------------------------------------------------------
# 2. Non-English PDFs (2)
# ---------------------------------------------------------------------------

make_digital_form_pdf(
    "icsr-07-es-form.pdf",
    "Informe de Reaccion Adversa",
    {
        "Paciente": {"Edad": "60", "Sexo": "Femenino", "Antecedentes": "Ninguno relevante"},
        "Notificante": {"Nombre": "Dra. Fernanda Ruiz", "Rol": "Medico tratante", "Pais": "Espana"},
        "Producto": {"Nombre": "Neptazol", "Dosis": "20 mg una vez al dia", "Via": "Oral",
                      "Fecha de inicio": "2026-07-05", "Fecha de suspension": "2026-07-12"},
        "Reaccion": {"Descripcion": "Erupcion cutanea generalizada y fiebre",
                      "Inicio": "2026-07-11", "Desenlace": "Hospitalizacion de un dia, luego resuelta"},
        "Gravedad": {"Grave": "Si", "Criterio": "Hospitalizacion"},
    },
)
write_email(
    "icsr-07-es", "dra.fernanda.ruiz@clinicasalud.example",
    "Reporte de reaccion adversa - Paciente con Neptazol",
    "2026-07-13T10:00:00Z",
    "Estimado equipo de farmacovigilancia,\n\nAdjunto el informe de una paciente que presento una reaccion "
    "adversa grave al Neptazol y requirio hospitalizacion. Quedo atenta a cualquier consulta.\n\nSaludos,\n"
    "Dra. Fernanda Ruiz",
    attachments=["icsr-07-es-form.pdf"],
    note="Non-English (Spanish) ICSR PDF",
)

make_digital_form_pdf(
    "icsr-08-fr-form.pdf",
    "Rapport d'Effet Indesirable",
    {
        "Patient": {"Age": "38", "Sexe": "Masculin", "Antecedents": "Aucun antecedent notable"},
        "Notifiant": {"Nom": "Dr. Lucas Moreau", "Role": "Medecin traitant", "Pays": "France"},
        "Produit": {"Nom": "Ferbolex", "Dose": "500 mg deux fois par jour", "Voie": "Orale",
                     "Date de debut": "2026-07-14", "Date d'arret": "2026-07-18"},
        "Reaction": {"Description": "Eruption cutanee et demangeaisons importantes",
                      "Debut": "2026-07-17", "Evolution": "Resolue apres arret du traitement"},
        "Gravite": {"Grave": "Non", "Critere": "Non applicable"},
    },
)
write_email(
    "icsr-08-fr", "dr.lucas.moreau@santeclinique.example",
    "Rapport d'effet indesirable - Patient sous Ferbolex",
    "2026-07-19T09:45:00Z",
    "Bonjour,\n\nVeuillez trouver ci-joint le rapport concernant un patient ayant presente une reaction cutanee "
    "sous Ferbolex. La reaction s'est resolue apres l'arret du traitement.\n\nCordialement,\nDr. Lucas Moreau",
    attachments=["icsr-08-fr-form.pdf"],
    note="Non-English (French) ICSR PDF",
)

# ---------------------------------------------------------------------------
# 3. Article PDFs describing a fictional patient case (5)
# ---------------------------------------------------------------------------

articles = [
    dict(filename="article-01.pdf",
         title="Anaphylaxis Following Administration of Neptazol: A Case Report",
         authors="Chen H, Alvarez M, Osei K.",
         journal="J. Clin. Pharmacovigilance Reports, Vol 12(3), 2026",
         abstract="We report a case of anaphylaxis in a 45-year-old man shortly after receiving Neptazol for "
                  "hypertension management, highlighting the importance of prompt recognition and reporting.",
         case_report="A 45-year-old man with no known drug allergies was prescribed Neptazol 20mg daily. "
                      "Approximately 30 minutes after his first dose, he developed facial flushing, throat "
                      "tightness, and difficulty breathing. He was treated with intramuscular epinephrine in the "
                      "emergency department and recovered fully within several hours. Neptazol was discontinued "
                      "and not rechallenged.",
         discussion="This case underscores the need for vigilance with first-dose reactions to Neptazol. "
                     "Clinicians should counsel patients on early warning signs of anaphylaxis.",
         references=["Smith J et al. Drug hypersensitivity reactions. Int J Pharmacovigilance 2024;9:112-119.",
                     "WHO Guidelines on ADR reporting, 2023."]),
    dict(filename="article-02.pdf",
         title="Pediatric Hepatotoxicity Associated with Ferbolex: A Case Report",
         authors="Nakamura T, Silva R.",
         journal="Pediatric Drug Safety Bulletin, Vol 8(1), 2026",
         abstract="A case of elevated liver enzymes in an 8-year-old child following a course of Ferbolex "
                  "syrup is described, with resolution after drug withdrawal.",
         case_report="An 8-year-old girl was given Ferbolex syrup 250mg twice daily for a respiratory infection. "
                      "After 5 days, she developed jaundice and fatigue. Liver function tests showed ALT and AST "
                      "levels approximately 6 times the upper limit of normal. Ferbolex was stopped and liver "
                      "enzymes normalized over the following three weeks.",
         discussion="Pediatric hepatotoxicity with Ferbolex is rarely reported; clinicians should monitor liver "
                     "function in children on extended courses.",
         references=["Park S et al. Pediatric drug-induced liver injury. J Pediatr Hepatol 2022;15:44-51."]),
    dict(filename="article-03.pdf",
         title="Severe Contact Dermatitis Linked to Cortivex Cream Use",
         authors="Dubois A, Meyer L.",
         journal="Dermatology Case Reviews, Vol 5(4), 2026",
         abstract="We describe a case of severe contact dermatitis in a 60-year-old woman using Cortivex Cream "
                  "for eczema, later linked to a manufacturing defect in the affected batch.",
         case_report="A 60-year-old woman applied Cortivex Cream to a small patch of eczema on her forearm. "
                      "Within a day she developed intense redness, swelling, and blistering extending beyond "
                      "the application site. She discontinued use and the reaction resolved over 10 days with "
                      "topical corticosteroid treatment. The product tube was later found to be from a batch "
                      "subject to a quality recall.",
         discussion="This case illustrates how a product quality defect can present clinically as a severe "
                     "adverse reaction, reinforcing the value of batch-level traceability.",
         references=["Quality Recall Notice, Cortivex batch B4471, 2026.",
                     "Lee C et al. Contact dermatitis from topical corticosteroids. Derm Rep 2021;11:200-206."]),
    dict(filename="article-04.pdf",
         title="Dizziness and Falls in Elderly Patients on Sedaprin: A Case Report",
         authors="Okafor N, Bianchi G.",
         journal="Geriatric Medicine Case Files, Vol 3(2), 2026",
         abstract="We present a case of a 71-year-old woman who experienced dizziness and a fall shortly after "
                  "starting Sedaprin, raising concerns about its use in elderly patients.",
         case_report="A 71-year-old woman was started on Sedaprin 10mg nightly for sleep difficulty. Two days "
                      "later she experienced dizziness upon standing and fell in her bathroom, sustaining a hip "
                      "bruise. Sedaprin was discontinued and no further falls occurred over the following month.",
         discussion="Orthostatic effects of Sedaprin may be underrecognized in older adults; lower starting "
                     "doses may be warranted in this population.",
         references=["Geriatric Pharmacology Society. Fall risk medications, 2025 update."]),
    dict(filename="article-05.pdf",
         title="Gastrointestinal Intolerance to Glucoreg: A Case Report",
         authors="Kim J, Haddad Y.",
         journal="Diabetes Care Reports, Vol 19(6), 2026",
         abstract="A case of persistent nausea and vomiting associated with Glucoreg in a middle-aged man is "
                  "reported, with symptom resolution following dose adjustment.",
         case_report="A 45-year-old man newly started on Glucoreg for type 2 diabetes reported nausea and "
                      "vomiting beginning approximately one hour after his morning dose, recurring on multiple "
                      "days. Symptoms were mild and did not require medical attention. Dose timing was adjusted "
                      "to be taken with food, after which symptoms improved substantially.",
         discussion="Mild GI intolerance to Glucoreg is a known but underreported issue; simple dosing "
                     "adjustments may improve tolerability without discontinuation.",
         references=["Diabetes Society Formulary Notes, 2026 edition."]),
]
for a in articles:
    make_article_pdf(a["filename"], a["title"], a["authors"], a["journal"], a["abstract"],
                      a["case_report"], a["discussion"], a["references"])

article_emails = [
    ("article-email-01", "dr.priya.nair@rivermed-clinic.example", "FYI - relevant published case report (Neptazol)",
     "2026-07-25T09:00:00Z",
     "Thought this published case report might be relevant to the Neptazol case I sent earlier - similar "
     "anaphylaxis presentation. Attaching for your files.", "article-01.pdf"),
    ("article-email-02", "safety.liaison@pedshealth-network.example", "Case report - Ferbolex pediatric hepatotoxicity",
     "2026-07-26T13:15:00Z",
     "Forwarding a case report our pediatric network came across regarding Ferbolex and liver enzyme elevation "
     "in a child. Please review for relevance.", "article-02.pdf"),
    ("article-email-03", "qa.director@citycare-pharmacy.example", "Published case tying Cortivex to batch defect",
     "2026-07-27T15:40:00Z",
     "Attached is a dermatology case report that appears to connect a Cortivex reaction to the same batch "
     "quality issue we flagged earlier (B4471).", "article-03.pdf"),
    ("article-email-04", "dr.owusu@brightpath-medical.example", "Case report - Sedaprin and falls in elderly patients",
     "2026-07-28T10:05:00Z",
     "Sharing a relevant case report about Sedaprin and fall risk in elderly patients, similar to what we saw "
     "with our patient Grace Owens.", "article-04.pdf"),
    ("article-email-05", "medinfo@wellcare-hospital.example", "Case report - Glucoreg GI tolerability",
     "2026-07-29T11:30:00Z",
     "Our medical information team found this case report on Glucoreg and GI side effects, thought it might be "
     "useful context for your team.", "article-05.pdf"),
]
for name, sender, subject, received, body, pdf in article_emails:
    write_email(name, sender, subject, received, body, attachments=[pdf], note="Article PDF - fictional patient case")

# ---------------------------------------------------------------------------
# 4. Quality-complaint-only (2)
# ---------------------------------------------------------------------------

damaged_img = make_damaged_product_image()
make_digital_form_pdf(
    "pqc-01-complaint.pdf",
    "Product Quality Complaint Form",
    {
        "Reporter": {"Name": "M. Alvarez", "Role": "Pharmacy technician", "Country": "USA"},
        "Product": {"Name": "Neptazol 20mg, bottle of 30", "Batch/Lot number": "B7788"},
        "Complaint": {"What's wrong": "Safety seal was broken on arrival; cap was loose",
                       "Photo mentioned": "Yes, photo attached"},
    },
    image_path=damaged_img,
    image_caption="Photo of the affected bottle:",
)
os.remove(damaged_img)  # already embedded into the PDF above; don't leave the intermediate PNG lying around
write_email(
    "pqc-01", "m.alvarez@westside-pharmacy.example",
    "Broken safety seal on Neptazol bottle - batch B7788",
    "2026-07-30T10:15:00Z",
    "We received a shipment of Neptazol and noticed one bottle (batch B7788) had a broken safety seal and a "
    "loose cap. No patient has taken from this bottle. Photo of the bottle is attached.",
    attachments=["pqc-01-complaint.pdf"],
    note="PQC-only - digital PDF with embedded image",
)

make_digital_form_pdf(
    "pqc-02-complaint.pdf",
    "Product Quality Complaint Form",
    {
        "Reporter": {"Name": "S. Whitfield", "Role": "Patient", "Country": "UK"},
        "Product": {"Name": "Ferbolex 500mg tablets", "Batch/Lot number": "B9012"},
        "Complaint": {"What's wrong": "Some tablets in the bottle are a noticeably different color than the rest",
                       "Photo mentioned": "No"},
    },
)
write_email(
    "pqc-02", "s.whitfield@mailservice.example",
    "Wrong color tablets found in bottle",
    "2026-07-31T18:20:00Z",
    "I opened a new bottle of Ferbolex and some of the tablets are a different color than the others. I haven't "
    "taken any of them yet. Batch number is B9012. Is this normal or should I return it?",
    attachments=["pqc-02-complaint.pdf"],
    note="PQC-only - digital PDF, no injury",
)

# ---------------------------------------------------------------------------
# 5. Info-request-only (2)
# ---------------------------------------------------------------------------

write_email(
    "mi-01", "patient.query22@webmail.example",
    "Question about taking Neptazol with ibuprofen",
    "2026-08-01T08:50:00Z",
    "Hi, I'm currently taking Neptazol for my blood pressure and I have a headache today. Is it safe to take "
    "ibuprofen with it, or should I use something else?",
    attachments=[],
    note="MI-only - simple dosing/interaction question",
)

write_email(
    "mi-02", "caregiver.jones@familymail.example",
    "Max dose of Ferbolex syrup for children?",
    "2026-08-02T12:00:00Z",
    "Could you tell me the maximum daily dose of Ferbolex syrup for a 6-year-old? The label doesn't cover that "
    "age range clearly and I want to make sure I'm dosing correctly.",
    attachments=[],
    note="MI-only - pediatric dosing question",
)

# ---------------------------------------------------------------------------
# 6. Clearly irrelevant (1)
# ---------------------------------------------------------------------------

write_email(
    "irrelevant-01", "newsletter@pharmapromo-events.example",
    "You're invited: Fall Product Showcase Webinar!",
    "2026-08-03T09:00:00Z",
    "Join us for our annual Fall Product Showcase! Learn about our newest formulations, meet the team, and "
    "enter our raffle for a $50 gift card. Register now - seats are limited!",
    attachments=[],
    note="Not Relevant - marketing email",
)

# ---------------------------------------------------------------------------
# Bonus (Section 4): literature-screening samples, uploaded independently of
# the mailbox via POST /api/literature/upload - not part of the Section 6
# required set above, kept in their own directory.
# ---------------------------------------------------------------------------

LITERATURE_DIR = os.path.join(HERE, "literature-samples")

make_article_pdf(
    "lit-multi-case.pdf",
    "Two Cases of Hypersensitivity Reactions to Neptazol in Clinical Practice",
    "Reyes M, Okonkwo B.",
    "Clinical Allergy Notes, Vol 7(2), 2026",
    abstract="We report two separate cases of hypersensitivity reactions following Neptazol administration, "
             "observed six months apart at the same clinic, illustrating a spectrum of severity.",
    case_report=(
        "Case 1: A 52-year-old woman was prescribed Neptazol 20mg daily for hypertension. Ten days into "
        "treatment she developed a generalized urticarial rash and mild lip swelling. She was treated with "
        "oral antihistamines as an outpatient and the drug was discontinued; symptoms resolved within 48 hours."
        "\n\n"
        "Case 2: A 67-year-old man on Neptazol 20mg daily for three weeks presented to the emergency department "
        "with facial angioedema and difficulty swallowing. He was treated with intramuscular epinephrine and "
        "intravenous corticosteroids and observed overnight before discharge. Neptazol was permanently "
        "discontinued and he was advised to avoid the drug class going forward."
    ),
    discussion="These two cases, differing markedly in severity, suggest clinicians should counsel all "
               "patients starting Neptazol on early signs of hypersensitivity, regardless of how mild an "
               "initial presentation might seem.",
    references=["Allergy Reporting Consortium. Drug hypersensitivity registry, 2025 annual report."],
    out_dir=LITERATURE_DIR,
)

make_article_pdf(
    "lit-no-case.pdf",
    "A Review of Post-Marketing Surveillance Practices for Antihypertensive Agents",
    "Dumont P.",
    "Pharmacovigilance Methods Review, Vol 14(1), 2026",
    abstract="This review summarizes general trends in adverse event reporting rates for antihypertensive "
             "medications over the past decade, without reference to any individual patient case.",
    case_report="This section is intentionally general: aggregate reporting rates for the antihypertensive "
                "drug class rose approximately 12% over the review period, attributed largely to improved "
                "reporting infrastructure rather than a genuine increase in event frequency. No single patient "
                "case is described in this review; all figures represent aggregated, de-identified statistics "
                "across multiple national databases.",
    discussion="Improving automated intake systems, such as the one described in this review, is likely to "
               "continue increasing reported volumes without a corresponding rise in true incidence.",
    references=["National Pharmacovigilance Database, Annual Statistical Summary, 2026."],
    out_dir=LITERATURE_DIR,
)

print("Generated test data:")
print(f"  PDFs:   {len([f for f in os.listdir(PDF_DIR) if f.endswith('.pdf')])} files in {PDF_DIR}")
print(f"  Emails: {len([f for f in os.listdir(EMAIL_DIR) if f.endswith('.json')])} files in {EMAIL_DIR}")
print(f"  Literature samples: {len([f for f in os.listdir(LITERATURE_DIR) if f.endswith('.pdf')])} files in {LITERATURE_DIR}")
