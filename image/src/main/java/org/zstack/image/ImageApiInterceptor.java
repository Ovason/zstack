package org.zstack.image;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.compute.vm.VmExtraInfoGetter;
import org.zstack.core.CoreGlobalProperty;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.Q;
import org.zstack.core.db.SQL;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.header.apimediator.ApiMessageInterceptionException;
import org.zstack.header.apimediator.ApiMessageInterceptor;
import org.zstack.header.image.*;
import org.zstack.header.image.ImageConstant.ImageMediaType;
import org.zstack.header.message.APIMessage;
import org.zstack.header.storage.backup.BackupStorageState;
import org.zstack.header.storage.backup.BackupStorageStatus;
import org.zstack.header.storage.backup.BackupStorageVO;
import org.zstack.header.storage.backup.BackupStorageVO_;
import org.zstack.header.storage.snapshot.VolumeSnapshotState;
import org.zstack.header.storage.snapshot.VolumeSnapshotStatus;
import org.zstack.header.storage.snapshot.VolumeSnapshotVO;
import org.zstack.header.volume.*;

import java.util.List;

import static org.zstack.core.Platform.argerr;
import static org.zstack.core.Platform.operr;

/**
 * Created with IntelliJ IDEA.
 * User: frank
 * Time: 4:35 PM
 * To change this template use File | Settings | File Templates.
 */
public class ImageApiInterceptor implements ApiMessageInterceptor {
    @Autowired
    private CloudBus bus;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private PluginRegistry pluginRgty;

    private static final String[] allowedProtocols = new String[]{
            "http://",
            "https://",
            "file:///",
            "upload://",
            "zstore://",
            "ftp://",
            "sftp://"
    };

    private void setServiceId(APIMessage msg) {
        if (msg instanceof ImageMessage) {
            ImageMessage imsg = (ImageMessage)msg;
            bus.makeTargetServiceIdByResourceUuid(msg, ImageConstant.SERVICE_ID, imsg.getImageUuid());
        }
    }

    @Override
    public APIMessage intercept(APIMessage msg) throws ApiMessageInterceptionException {
        if (msg instanceof APIAddImageMsg) {
            validate((APIAddImageMsg)msg);
        } else if (msg instanceof APICreateRootVolumeTemplateFromRootVolumeMsg) {
            validate((APICreateRootVolumeTemplateFromRootVolumeMsg) msg);
        } else if (msg instanceof APICreateRootVolumeTemplateFromVolumeSnapshotMsg) {
            validate((APICreateRootVolumeTemplateFromVolumeSnapshotMsg) msg);
        } else if (msg instanceof APICreateDataVolumeTemplateFromVolumeMsg) {
            validate((APICreateDataVolumeTemplateFromVolumeMsg) msg);
        } else if (msg instanceof APICreateDataVolumeTemplateFromVolumeSnapshotMsg) {
            validate((APICreateDataVolumeTemplateFromVolumeSnapshotMsg) msg);
        } else if (msg instanceof APISetImageBootModeMsg) {
            validate((APISetImageBootModeMsg) msg);
        }

        setServiceId(msg);
        return msg;
    }

    private void validate(APISetImageBootModeMsg msg){
        ImageVO vo = dbf.findByUuid(msg.getImageUuid(), ImageVO.class);
        if (ImageBootMode.Legacy.toString().equals(msg.getBootMode())
                && ImageArchitecture.aarch64.toString().equals(vo.getArchitecture())) {
            throw new ApiMessageInterceptionException(argerr("The aarch64 architecture does not support legacy."));
        }
    }

    private void validate(APICreateDataVolumeTemplateFromVolumeMsg msg) {
        VolumeVO vol = dbf.findByUuid(msg.getVolumeUuid(), VolumeVO.class);
        if (VolumeStatus.Ready != vol.getStatus()) {
            throw new ApiMessageInterceptionException(operr("volume[uuid:%s] is not Ready, it's %s", vol.getUuid(), vol.getStatus()));
        }

        if (VolumeState.Enabled != vol.getState()) {
            throw new ApiMessageInterceptionException(operr("volume[uuid:%s] is not Enabled, it's %s", vol.getUuid(), vol.getState()));
        }
    }

    private void validate(APICreateDataVolumeTemplateFromVolumeSnapshotMsg msg) {
        VolumeSnapshotVO vsvo = dbf.findByUuid(msg.getSnapshotUuid(), VolumeSnapshotVO.class);
        if (VolumeSnapshotStatus.Ready != vsvo.getStatus()) {
            throw new ApiMessageInterceptionException(operr("volume snapshot[uuid:%s] is not Ready, it's %s", vsvo.getUuid(), vsvo.getStatus()));
        }

        if (VolumeSnapshotState.Enabled != vsvo.getState()) {
            throw new ApiMessageInterceptionException(operr("volume snapshot[uuid:%s] is not Enabled, it's %s", vsvo.getUuid(), vsvo.getState()));
        }
    }

    protected void validate(APICreateRootVolumeTemplateFromVolumeSnapshotMsg msg) {
        ImageMessageFiller.fillFromSnapshot(msg, msg.getSnapshotUuid());
    }

    @Transactional(readOnly = true)
    protected void validate(APICreateRootVolumeTemplateFromRootVolumeMsg msg) {
        ImageMessageFiller.fillFromVolume(msg, msg.getRootVolumeUuid());
    }

    private void validate(APIAddImageMsg msg) {
        if (ImageMediaType.ISO.toString().equals(msg.getMediaType())) {
            msg.setFormat(ImageConstant.ISO_FORMAT_STRING);
        }

        if (msg.isSystem() && (ImageMediaType.ISO.toString().equals(msg.getMediaType()) || ImageConstant.ISO_FORMAT_STRING.equals(msg.getFormat()))) {
            throw new ApiMessageInterceptionException(argerr(
                    "ISO cannot be used as system image"
            ));
        }

        if (!VolumeFormat.hasType(msg.getFormat())) {
            throw new ApiMessageInterceptionException(argerr("unknown format[%s]", msg.getFormat()));
        }

        if (msg.getType() != null && !ImageType.hasType(msg.getType())) {
            throw new ApiMessageInterceptionException(argerr("unsupported image type[%s]", msg.getType()));
        }

        if (msg.getMediaType() == null) {
            msg.setMediaType(ImageMediaType.RootVolumeTemplate.toString());
        }

        ImageMessageFiller.fillDefault(msg);

        if (msg.getBackupStorageUuids() != null) {
            SimpleQuery<BackupStorageVO> q = dbf.createQuery(BackupStorageVO.class);
            q.select(BackupStorageVO_.uuid);
            q.add(BackupStorageVO_.status, Op.EQ, BackupStorageStatus.Connected);
            q.add(BackupStorageVO_.availableCapacity, Op.GT, 0);
            q.add(BackupStorageVO_.uuid, Op.IN, msg.getBackupStorageUuids());
            List<String> bsUuids = q.listValue();
            if (bsUuids.isEmpty()) {
                throw new ApiMessageInterceptionException(operr("no backup storage specified in uuids%s is available for adding this image; they are not in status %s or not in state %s, or the uuid is invalid backup storage uuid",
                                msg.getBackupStorageUuids(), BackupStorageStatus.Connected, BackupStorageState.Enabled));
            }
            isValidBS(bsUuids);
            msg.setBackupStorageUuids(bsUuids);
        }

        // compatible with file:/// and /
        if (msg.getUrl().startsWith("/")) {
            msg.setUrl(String.format("file://%s", msg.getUrl()));
        } else if (!isValidProtocol(msg.getUrl())) {
            throw new ApiMessageInterceptionException(argerr("url must starts with 'file:///', 'http://', 'https://'， 'ftp://', 'sftp://' or '/'"));
        }
    }

    private void isValidBS(List<String> bsUuids) {
        for (AddImageExtensionPoint ext : pluginRgty.getExtensionList(AddImageExtensionPoint.class)) {
            ext.validateAddImage(bsUuids);
        }
    }

    private static boolean isValidProtocol(String url) {
        for (String p : allowedProtocols) {
            if (url.startsWith(p)) {
                return true;
            }
        }

        return false;
    }

}
