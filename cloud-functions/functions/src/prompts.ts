import {firestore} from "firebase-admin";
import DocumentData = firestore.DocumentData;

export const priceEstimationPrompt = (data: DocumentData) =>
  `Based on the car make:${data.make},model ${data.model}, 
mileage: ${data.mileage}, and year: ${data.year},
 generate an estimated car price car and store
     it in a field called estimatedCarPrice. 
     The value should be double express as
      a currency (US dollars with dollar sign).
       If impossible to get the estimatedCarPrice
       then set it to an empty string. using this JSON schema:
            Price = {'estimatedCarPrice': string}
            Return: Object<string>`;
