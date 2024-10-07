import * as logger from "firebase-functions/logger";
import {initializeApp} from "firebase-admin/app";
//  The updated import path is:
import {onDocumentUpdated} from "firebase-functions/v2/firestore";
import {GoogleGenerativeAI} from "@google/generative-ai";
import {firestore} from "firebase-admin";
import DocumentData = firestore.DocumentData;
import {priceEstimationPrompt} from "./prompts";

initializeApp();

/**
 * This function handles creating the GEMINI prompt and
 * parsing it as a json object
 *
 * @param {DocumentData} data - The doc reference for the carInfo table.
 */
async function getEstimatedCarPrice(data: DocumentData):
    Promise<number | null> {
  const API_KEY = process.env.API_KEY;
  if (API_KEY === undefined) {
    logger.info("No API key found");
    return null;
  }

  try {
    const genAI = new GoogleGenerativeAI(API_KEY);
    const model = genAI.getGenerativeModel({
      model: "gemini-1.5-flash",
      generationConfig: {
        responseMimeType: "application/json",
      },
    });
    const prompt = priceEstimationPrompt(data);
    const result = await model.generateContent(prompt);
    const jsonData = result.response.text();
    logger.info(`Gemini response: ${jsonData}`);
    return JSON.parse(jsonData).estimatedCarPrice;
  } catch (e) {
    logger.error("Error generating car price from Gemini service:", e);
    return null;
  }
}


export const estimatedCarPriceOnUploadUpdater = onDocumentUpdated(
  "carInfo/{id}",
  async (event) => {
    const beforeData: DocumentData | undefined = event.data?.before.data();
    const afterData: DocumentData | undefined = event.data?.after.data();

    // Ensure the document has data to work with
    if (!afterData || afterData.make === "" || afterData.mileage === "" ||
        afterData.model === "" || afterData.year === "") {
      logger.info(
        "Document does not have enough data to calculate estimated price: ",
        afterData);
      return null;
    }

    // Check if the relevant data (make, model, mileage, year)
    // has changed or if the price is already set
    const hasRelevantDataChanged =
            afterData.make !== beforeData?.make ||
            afterData.model !== beforeData?.model ||
            afterData.mileage !== beforeData?.mileage ||
            afterData.year !== beforeData?.year;

    const estimatedCarPrice = await getEstimatedCarPrice(afterData);
    // Only update Firestore if the estimated price has changed
    if (hasRelevantDataChanged) {
      logger.info("Estimated car price updated:", estimatedCarPrice);
      return event.data?.after.ref.set(
        {estimatedCarPrice},
        {merge: true}
      );
    } else {
      return null;
    }
  });
