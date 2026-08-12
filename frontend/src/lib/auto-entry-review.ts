export type AutoEntryFieldStatus = "OK" | "REVIEW" | "MISSING";

export type AutoEntryFindingCode =
  | "LOW_CONFIDENCE"
  | "ENUM_VALUE_UNKNOWN"
  | "LINE_AMOUNT_INCONSISTENT"
  | "TAX_BREAKDOWN_INCONSISTENT"
  | "TAX_TOTAL_INCONSISTENT"
  | "TOTAL_INCONSISTENT"
  | "ADJUSTMENT_DIRECTION_UNKNOWN"
  | "TAX_MODE_AMBIGUOUS"
  | "PAYMENT_DUE_BEFORE_ISSUE_DATE";

export type AutoEntryPoint = { x: number; y: number };

export type AutoEntrySourceRef = { pageNumber: number; polygon: AutoEntryPoint[] };

export type AutoEntryField<T> = {
  value: T | null;
  confidence: number | null;
  status: AutoEntryFieldStatus;
  sources: AutoEntrySourceRef[];
  findings: AutoEntryFindingCode[];
};

export type AutoEntryDerivedField<T> = {
  value: T | null;
  status: AutoEntryFieldStatus;
  findings: AutoEntryFindingCode[];
};

export type AutoEntryObjectReview = {
  confidence: number | null;
  status: AutoEntryFieldStatus;
  sources: AutoEntrySourceRef[];
  findings: AutoEntryFindingCode[];
};

export type AutoEntryPageRef = {
  pageNumber: number;
  width: number;
  height: number;
  unit: string;
  angleDegrees: number | null;
};

export type AutoEntryLineItem = {
  review: AutoEntryObjectReview;
  itemDate: AutoEntryField<string>;
  productCode: AutoEntryField<string>;
  itemDescription: AutoEntryField<string>;
  quantity: AutoEntryField<number>;
  unit: AutoEntryField<string>;
  unitPriceAmount: AutoEntryField<number>;
  taxIndicator: AutoEntryField<string>;
  taxRatePercent: AutoEntryField<number>;
  taxCategory: AutoEntryField<string>;
  lineAmount: AutoEntryField<number>;
};

export type AutoEntryTaxBreakdown = {
  review: AutoEntryObjectReview;
  taxRatePercent: AutoEntryField<number>;
  taxableAmount: AutoEntryField<number>;
  taxAmount: AutoEntryField<number>;
  categoryNotation: AutoEntryField<string>;
  category: AutoEntryField<string>;
};

export type AutoEntryAdjustment = {
  review: AutoEntryObjectReview;
  type: AutoEntryField<string>;
  direction: AutoEntryField<string>;
  description: AutoEntryField<string>;
  rawAmount: AutoEntryField<number>;
  normalizedSignedAmount: AutoEntryDerivedField<number>;
};

export type AutoEntryBankTransferDestination = {
  bankName: AutoEntryField<string>;
  branchName: AutoEntryField<string>;
  accountType: AutoEntryField<string>;
  accountNumber: AutoEntryField<string>;
  accountHolderName: AutoEntryField<string>;
};

export type AutoEntryReviewDocument = {
  documentType: AutoEntryField<string>;
  documentNumber: AutoEntryField<string>;
  issueDate: AutoEntryField<string>;
  issuerTaxRegistrationNumber: AutoEntryField<string>;
  recipientName: AutoEntryField<string>;
  recipientDepartment: AutoEntryField<string>;
  recipientContactPerson: AutoEntryField<string>;
  recipientPostalCode: AutoEntryField<string>;
  recipientAddress: AutoEntryField<string>;
  issuerName: AutoEntryField<string>;
  issuerDepartment: AutoEntryField<string>;
  issuerContactPerson: AutoEntryField<string>;
  issuerPostalCode: AutoEntryField<string>;
  issuerAddress: AutoEntryField<string>;
  issuerPhoneNumber: AutoEntryField<string>;
  issuerEmail: AutoEntryField<string>;
  subject: AutoEntryField<string>;
  currencyCode: AutoEntryField<string>;
  lineItems: AutoEntryField<AutoEntryLineItem[]>;
  subtotalAmount: AutoEntryField<number>;
  taxAmount: AutoEntryField<number>;
  totalAmount: AutoEntryField<number>;
  taxBreakdown: AutoEntryField<AutoEntryTaxBreakdown[]>;
  adjustments: AutoEntryField<AutoEntryAdjustment[]>;
  taxInclusionNotation: AutoEntryField<string>;
  paymentDueDate: AutoEntryField<string>;
  bankTransferDestination: AutoEntryField<AutoEntryBankTransferDestination>;
};

export type AutoEntryReviewSummary = {
  fieldCount: number;
  okCount: number;
  reviewCount: number;
  missingCount: number;
};

export type AutoEntryReviewResponse = {
  analysisId: string;
  schemaVersion: string;
  pages: AutoEntryPageRef[];
  document: AutoEntryReviewDocument;
  taxMode: AutoEntryDerivedField<"TAX_INCLUDED" | "TAX_EXCLUDED" | "UNKNOWN">;
  summary: AutoEntryReviewSummary;
};
