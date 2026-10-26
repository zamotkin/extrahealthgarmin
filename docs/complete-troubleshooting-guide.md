# Complete Guide: Troubleshooting Garmin Connect Import Issues

> **Historical note:** this guide covers the v1 CSV-converter flow that was removed in
> v2.0.0 (the app now syncs via Health Connect and uploads FIT files directly). Kept for
> reference only.

## Step-by-Step Troubleshooting Process

### Step 1: Initial File Verification
1. **Open the CSV file in a plain text editor** (Never Excel!)
2. **Check the first line** contains exactly: `Date,Weight,BMI,Fat`
3. **Verify date format** - all dates must be `YYYY-MM-DD`
4. **Check decimal separators** - all numbers must use `.` not `,`
5. **Look for empty rows** - remove any completely blank lines

### Step 2: Common Error Resolution

#### If you get "Upload Error":
- Wait 2-3 minutes
- Check internet connection stability
- Try a different browser
- Reduce file size (split into smaller files)

#### If you get "File Not Supported":
- Confirm file ends with `.csv` not `.xlsx`
- Verify file wasn't opened in Excel
- Check for semicolon delimiters instead of commas

#### If you get "Missing Fields":
- Ensure no empty header cells
- Check for extra spaces in column names
- Verify all required columns exist

#### If you get "Invalid Format":
- Dates must be `2024-01-15`, not `15.01.2024` or `15-Jan-2024`
- Numbers must be `155.5`, not `155,5`
- No text characters in numeric fields

### Step 3: Advanced Troubleshooting

#### File Size Issues
**Large Files (>500 records):**
- Split into multiple files of 50-100 records each
- Upload files sequentially
- Consider manual data entry for very old records

**Very Small Files (<5 records):**
- Add a few more recent records if possible
- Ensure at least 30 days of data for meaningful trends

#### Browser-Specific Issues
**Chrome/Chrome:**
- Most reliable option
- Clear cache if uploads fail repeatedly
- Disable ad blockers temporarily

**Firefox:**
- Good alternative to Chrome
- May work when Chrome fails

**Safari (Mac/iPhone):**
- Sometimes has issues with large files
- Try Chrome or Firefox as alternative

**Edge:**
- Mixed reliability
- Prefer Chrome/Firefox if available

### Step 4: Data Quality Checks

#### Required CSV Structure:
```csv
Date,Weight,BMI,Fat
2024-01-15,155.5,23.5,18.2
2024-01-16,154.8,23.4,18.1
```

#### Common Data Problems:
1. **Russian Months:** `янв`, `фев` → Use numbers: `01`, `02`
2. **Wrong Delimiters:** `;` or tabs → Use commas: `,`
3. **Extra Quotes:** `"155.5"` → Remove quotes: `155.5`
4. **Missing Values:** `,,18.2` → Add all required data: `155.5,23.5,18.2`

### Step 5: Alternative Upload Methods

#### If CSV Consistently Fails:
1. **Try Manual Entry:**
   - Enter recent data manually into Garmin Connect
   - Use CSV for historical reference

2. **Convert to TCX Format:**
   - Use online converters or specialized tools
   - TCX format sometimes works when CSV fails

3. **Garmin Express Desktop App:**
   - Install Garmin Express on your computer
   - Try importing through the desktop application

### Step 6: Prevention Best Practices

#### Before Export from Tefal App:
- Ensure your Tefal app is updated
- Export recent data first (test with smaller dataset)
- Check Tefal app settings for CSV export options

#### After Export:
- Immediately verify file in text editor
- Never open in Excel for verification
- Keep original file as backup

#### Regular Maintenance:
- Upload data weekly rather than monthly
- Keep records under 200 entries per file
- Maintain consistent naming convention

## Quick Reference Flowchart

```
CSV Export Failed?
├── Check file in text editor
│   ├── Wrong headers? → Fix: Date,Weight,BMI,Fat
│   ├── Wrong dates? → Fix: YYYY-MM-DD format
│   ├── Wrong decimals? → Fix: Use . not ,
│   └── Empty rows? → Remove blank lines
│
└── Still failing?
    ├── Try different browser
    ├── Split into smaller files
    └── Contact Garmin Support
```

## File Preparation Checklist (Print This)

### Before Upload:
- [ ] File saved as .csv (never .xlsx)
- [ ] Opened in text editor (not Excel)
- [ ] Headers: Date,Weight,BMI,Fat
- [ ] All dates: YYYY-MM-DD
- [ ] All decimals: use '.' separator
- [ ] No empty rows
- [ ] File size < 5MB
- [ ] < 200 records per file

### After Upload Attempt:
- [ ] Success? → Great! Check data in Garmin Connect
- [ ] Still failing? → Try steps above
- [ ] Multiple failures? → Contact Garmin Support

## Contact Information

### Garmin Support Resources:
- **Garmin Connect Status:** https://status.garmin.com
- **Support Forums:** https://forums.garmin.com
- **Customer Support:** Check your region for contact details

### When Contacting Support, Include:
1. Exact error message
2. File format and size
3. Browser and device used
4. Steps you've tried
5. Screenshot of error (if possible)

---

## Expert Tips

### Batch Upload Strategy:
- Upload 30-50 records at a time for most reliable results
- Start with most recent data, then add older records
- Use consistent upload schedule (weekly recommended)

### Data Organization:
- Keep original Tefal exports as backups
- Maintain separate files for different time periods
- Document any manual changes made

### Long-term Solutions:
- Consider using Garmin's own scale for direct sync
- Check for Garmin Connect mobile app updates
- Monitor Garmin forums for known issues and fixes

---

**Remember:** The most common cause of import failures is opening CSV files in Excel, which automatically changes the formatting. Always use a plain text editor to verify your file contents before uploading to Garmin Connect.