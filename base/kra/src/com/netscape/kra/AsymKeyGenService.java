//--- BEGIN COPYRIGHT BLOCK ---
//This program is free software; you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation; version 2 of the License.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License along
//with this program; if not, write to the Free Software Foundation, Inc.,
//51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
//(C) 2014 Red Hat, Inc.
//All rights reserved.
//--- END COPYRIGHT BLOCK ---
package com.netscape.kra;

import java.math.BigInteger;
import java.security.KeyPair;

import org.mozilla.jss.crypto.KeyPairGeneratorSpi;
import org.mozilla.jss.crypto.PrivateKey;
import org.mozilla.jss.netscape.security.util.WrappingParams;

import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.dbs.keydb.IKeyRecord;
import com.netscape.certsrv.dbs.keydb.IKeyRepository;
import com.netscape.certsrv.dbs.keydb.KeyId;
import com.netscape.certsrv.key.AsymKeyGenerationRequest;
import com.netscape.certsrv.key.KeyRequestResource;
import com.netscape.certsrv.kra.IKeyRecoveryAuthority;
import com.netscape.certsrv.logging.ILogger;
import com.netscape.certsrv.logging.event.AsymKeyGenerationProcessedEvent;
import com.netscape.certsrv.request.IRequest;
import com.netscape.certsrv.request.IService;
import com.netscape.certsrv.request.RequestId;
import com.netscape.certsrv.security.IStorageKeyUnit;
import com.netscape.cms.logging.Logger;
import com.netscape.cms.logging.SignedAuditLogger;
import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.apps.CMSEngine;
import com.netscape.cmscore.apps.EngineConfig;
import com.netscape.cmscore.dbs.KeyRecord;

/**
 * Service class to handle asymmetric key generation requests.
 * A new asymmetric key is generated and archived the database as a key record.
 * The private key is wrapped with the storage key and stored in the privateKeyData attribute of the
 * ldap record.
 * The public key is stored in the publicKeyData attribute of the record.
 *
 * @author akoneru
 *
 */
public class AsymKeyGenService implements IService {

    public static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AsymKeyGenService.class);
    private static Logger signedAuditLogger = SignedAuditLogger.getLogger();

    private static final String ATTR_KEY_RECORD = "keyRecord";
    private static final String STATUS_ACTIVE = "active";

    private IKeyRecoveryAuthority kra = null;
    private IStorageKeyUnit storageUnit = null;

    public AsymKeyGenService(IKeyRecoveryAuthority kra) {
        this.kra = kra;
        this.storageUnit = kra.getStorageKeyUnit();
    }

    @Override
    public boolean serviceRequest(IRequest request) throws EBaseException {
        CMSEngine engine = CMS.getCMSEngine();
        EngineConfig configStore = engine.getConfig();
        String clientKeyId = request.getExtDataInString(IRequest.SECURITY_DATA_CLIENT_KEY_ID);
        String algorithm = request.getExtDataInString(IRequest.KEY_GEN_ALGORITHM);

        String keySizeStr = request.getExtDataInString(IRequest.KEY_GEN_SIZE);
        int keySize = Integer.valueOf(keySizeStr);

        String realm = request.getRealm();

        boolean allowEncDecrypt_archival = configStore.getBoolean("kra.allowEncDecrypt.archival", false);

        KeyPairGeneratorSpi.Usage[] usageList = null;
        String usageStr = request.getExtDataInString(IRequest.KEY_GEN_USAGES);
        if (usageStr != null) {
            String[] usages = usageStr.split(",");

            if (usages.length > 0) {
                usageList = new KeyPairGeneratorSpi.Usage[usages.length];
                for (int i = 0; i < usages.length; i++) {
                    switch (usages[i]) {
                    case AsymKeyGenerationRequest.DECRYPT:
                        usageList[i] = KeyPairGeneratorSpi.Usage.DECRYPT;
                        break;
                    case AsymKeyGenerationRequest.ENCRYPT:
                        usageList[i] = KeyPairGeneratorSpi.Usage.ENCRYPT;
                        break;
                    case AsymKeyGenerationRequest.WRAP:
                        usageList[i] = KeyPairGeneratorSpi.Usage.WRAP;
                        break;
                    case AsymKeyGenerationRequest.UNWRAP:
                        usageList[i] = KeyPairGeneratorSpi.Usage.UNWRAP;
                        break;
                    case AsymKeyGenerationRequest.DERIVE:
                        usageList[i] = KeyPairGeneratorSpi.Usage.DERIVE;
                        break;
                    case AsymKeyGenerationRequest.SIGN:
                        usageList[i] = KeyPairGeneratorSpi.Usage.SIGN;
                        break;
                    case AsymKeyGenerationRequest.SIGN_RECOVER:
                        usageList[i] = KeyPairGeneratorSpi.Usage.SIGN_RECOVER;
                        break;
                    case AsymKeyGenerationRequest.VERIFY:
                        usageList[i] = KeyPairGeneratorSpi.Usage.VERIFY;
                        break;
                    case AsymKeyGenerationRequest.VERIFY_RECOVER:
                        usageList[i] = KeyPairGeneratorSpi.Usage.VERIFY_RECOVER;
                        break;
                    }
                }
            } else {
                usageList = new KeyPairGeneratorSpi.Usage[2];
                usageList[0] = KeyPairGeneratorSpi.Usage.DECRYPT;
                usageList[1] = KeyPairGeneratorSpi.Usage.ENCRYPT;
            }
        }

        logger.debug("AsymKeyGenService: request id: " + request.getRequestId());
        logger.debug("AsymKeyGenService: algorithm: " + algorithm);

        String owner = request.getExtDataInString(IRequest.ATTR_REQUEST_OWNER);
        String auditSubjectID = owner;

        // Generating the asymmetric keys
        KeyPair kp = null;

        try {
            kp = kra.generateKeyPair(
                    algorithm.toUpperCase(),
                    keySize,
                    null, // keyCurve for ECC, not yet supported
                    null, // PQG not yet supported
                    usageList
                 );

        } catch (EBaseException e) {
            String message = "Unable to generate asymmetric key: " + e.getMessage();
            logger.error("AsymKeyGenService: " + message, e);
            auditAsymKeyGenRequestProcessed(auditSubjectID, ILogger.FAILURE, request.getRequestId(),
                    clientKeyId, null, message);
            throw new EBaseException(message, e);
        }

        if (kp == null) {
            String message = "Unable to generate asymmetric key";
            logger.error("AsymKeyGenService: " + message);
            auditAsymKeyGenRequestProcessed(auditSubjectID, ILogger.FAILURE, request.getRequestId(),
                    clientKeyId, null, message);
            throw new EBaseException(message);
        }

        byte[] privateSecurityData = null;
        WrappingParams params = null;

        try {
            params = storageUnit.getWrappingParams(allowEncDecrypt_archival);
            privateSecurityData = storageUnit.wrap((PrivateKey) kp.getPrivate(), params);
        } catch (Exception e) {
            String message = "Unable to wrap asymmetric key: " + e.getMessage();
            logger.error("AsymKeyGenService: " + message, e);
            auditAsymKeyGenRequestProcessed(auditSubjectID, ILogger.FAILURE, request.getRequestId(),
                    clientKeyId, null, message);
            throw new EBaseException(message, e);
        }

        KeyRecord record = new KeyRecord(null, kp.getPublic().getEncoded(), privateSecurityData,
                owner, algorithm, owner);

        IKeyRepository storage = kra.getKeyRepository();
        BigInteger serialNo = storage.getNextSerialNumber();

        if (serialNo == null) {
            String message = "Unable to get next key ID";
            logger.error("AsymKeyGenService: " + message);
            auditAsymKeyGenRequestProcessed(auditSubjectID, ILogger.FAILURE, request.getRequestId(),
                    clientKeyId, null, message);
            throw new EBaseException(message);
        }

        // Storing the public key and private key.
        record.set(IKeyRecord.ATTR_CLIENT_ID, clientKeyId);
        record.setSerialNumber(serialNo);
        record.set(KeyRecord.ATTR_ID, serialNo);
        record.set(KeyRecord.ATTR_DATA_TYPE, KeyRequestResource.ASYMMETRIC_KEY_TYPE);
        record.set(KeyRecord.ATTR_STATUS, STATUS_ACTIVE);
        record.set(KeyRecord.ATTR_KEY_SIZE, keySize);
        request.setExtData(ATTR_KEY_RECORD, serialNo);

        if (realm != null) {
            record.set(KeyRecord.ATTR_REALM, realm);
        }

        try {
            record.setWrappingParams(params, allowEncDecrypt_archival);
        } catch (Exception e) {
            String message = "Unable to store wrapping parameters: " + e.getMessage();
            logger.error("AsymKeyGenService: " + message);
            auditAsymKeyGenRequestProcessed(auditSubjectID, ILogger.FAILURE, request.getRequestId(),
                    clientKeyId, null, message);
            throw new EBaseException(message, e);
        }

        storage.addKeyRecord(record);

        auditAsymKeyGenRequestProcessed(auditSubjectID, ILogger.SUCCESS, request.getRequestId(),
                clientKeyId, new KeyId(serialNo), "None");
        request.setExtData(IRequest.RESULT, IRequest.RES_SUCCESS);
        kra.getRequestQueue().updateRequest(request);
        return true;
    }

    private void auditAsymKeyGenRequestProcessed(String subjectID, String status, RequestId requestID,
            String clientKeyID,
            KeyId keyID, String reason) {
        signedAuditLogger.log(new AsymKeyGenerationProcessedEvent(
                subjectID,
                status,
                requestID,
                clientKeyID,
                keyID,
                reason));
    }
}
