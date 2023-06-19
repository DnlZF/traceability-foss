/********************************************************************************
 * Copyright (c) 2022, 2023 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 * Copyright (c) 2022, 2023 ZF Friedrichshafen AG
 * Copyright (c) 2022, 2023 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ********************************************************************************/

package org.eclipse.tractusx.traceability.assets.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.tractusx.traceability.assets.application.rest.service.AssetService;
import org.eclipse.tractusx.traceability.assets.domain.asbuilt.AssetAsBuiltRepository;
import org.eclipse.tractusx.traceability.assets.domain.asplanned.AssetAsPlannedRepository;
import org.eclipse.tractusx.traceability.assets.domain.base.IrsRepository;
import org.eclipse.tractusx.traceability.assets.domain.model.Asset;
import org.eclipse.tractusx.traceability.assets.domain.model.Owner;
import org.eclipse.tractusx.traceability.assets.domain.model.QualityType;
import org.eclipse.tractusx.traceability.assets.infrastructure.base.irs.model.request.BomLifecycle;
import org.eclipse.tractusx.traceability.assets.infrastructure.base.irs.model.response.Direction;
import org.eclipse.tractusx.traceability.assets.infrastructure.base.irs.model.response.relationship.Aspect;
import org.eclipse.tractusx.traceability.common.config.AssetsAsyncConfig;
import org.eclipse.tractusx.traceability.common.model.PageResult;
import org.eclipse.tractusx.traceability.qualitynotification.domain.model.QualityNotification;
import org.eclipse.tractusx.traceability.qualitynotification.domain.model.QualityNotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssetServiceImpl implements AssetService {

    private final AssetAsBuiltRepository assetAsBuiltRepository;
    private final AssetAsPlannedRepository assetAsPlannedRepository;
    private final IrsRepository irsRepository;

    @Async(value = AssetsAsyncConfig.SYNCHRONIZE_ASSETS_EXECUTOR)
    public void synchronizeAssetsAsync(List<String> globalAssetIds) {
        for (String globalAssetId : globalAssetIds) {
            try {
                synchronizeAssetsAsync(globalAssetId);
            } catch (Exception e) {
                log.warn("Cannot fetch assets for id: {}. Error: {}", globalAssetId, e.getMessage());
            }
        }
    }

    @Async(value = AssetsAsyncConfig.SYNCHRONIZE_ASSETS_EXECUTOR)
    public void synchronizeAssetsAsync(String globalAssetId) {
        log.info("Synchronizing assets for globalAssetId: {}", globalAssetId);
        try {
            syncAssetsAsBuilt(globalAssetId);
            syncAssetsAsPlanned(globalAssetId);

        } catch (Exception e) {
            log.warn("Exception during assets synchronization for globalAssetId: {}. Message: {}.", globalAssetId, e.getMessage(), e);
        }
    }

    private void syncAssetsAsPlanned(String globalAssetId) {
        List<Asset> downwardAssets = irsRepository.findAssets(globalAssetId, Direction.DOWNWARD, Aspect.downwardAspectsForAssetsAsPlanned(), BomLifecycle.AS_PLANNED);
        downwardAssets.forEach(asset -> {
            log.info(asset.getId() + "isDownwardAsset - asPlanned");
        });
        assetAsPlannedRepository.saveAll(downwardAssets);
    }

    private void syncAssetsAsBuilt(String globalAssetId) {
        List<Asset> downwardAssets = irsRepository.findAssets(globalAssetId, Direction.DOWNWARD, Aspect.downwardAspectsForAssetsAsBuilt(), BomLifecycle.AS_BUILT);
        downwardAssets.forEach(asset -> {
            log.info(asset.getId() + "isDownwardAsset - asBuilt");
        });

        assetAsBuiltRepository.saveAll(downwardAssets);

        List<Asset> upwardAssets = irsRepository.findAssets(globalAssetId, Direction.UPWARD, Aspect.upwardAspectsForAssetsAsBuilt(), BomLifecycle.AS_BUILT);

        upwardAssets.forEach(asset -> {
            if (assetAsBuiltRepository.existsById(asset.getId())) {
                log.info(asset.getId() + "isUpwardAsset 1 - asBuilt");
                assetAsBuiltRepository.updateParentDescriptionsAndOwner(asset);
            } else {
                log.info(asset.getId() + "isUpwardAsset 2 - asBuilt");
                assetAsBuiltRepository.save(asset);
            }
        });
    }


    public void setAssetsInvestigationStatus(QualityNotification investigation) {
        assetAsBuiltRepository.getAssetsById(investigation.getAssetIds()).forEach(asset -> {
            // Assets in status closed will be false, others true
            asset.setUnderInvestigation(!investigation.getNotificationStatus().equals(QualityNotificationStatus.CLOSED));
            assetAsBuiltRepository.save(asset);
        });
    }

    public void setAssetsAlertStatus(QualityNotification alert) {
        assetAsBuiltRepository.getAssetsById(alert.getAssetIds()).forEach(asset -> {
            // Assets in status closed will be false, others true
            asset.setActiveAlert(!alert.getNotificationStatus().equals(QualityNotificationStatus.CLOSED));
            assetAsBuiltRepository.save(asset);
        });
    }

    public Asset updateQualityType(String assetId, QualityType qualityType) {
        Asset foundAsset = assetAsBuiltRepository.getAssetById(assetId);
        foundAsset.setQualityType(qualityType);
        return assetAsBuiltRepository.save(foundAsset);
    }

    public Map<String, Long> getAssetsCountryMap() {
        return assetAsBuiltRepository.getAssets().stream()
                .collect(Collectors.groupingBy(asset -> asset.getSemanticModel().getManufacturingCountry(), Collectors.counting()));
    }

    public void saveAssets(List<Asset> assets) {
        assetAsBuiltRepository.saveAll(assets);
    }

    public PageResult<Asset> getAssets(Pageable pageable, Owner owner) {
        return assetAsBuiltRepository.getAssets(pageable, owner);
    }

    public Asset getAssetById(String assetId) {
        return assetAsBuiltRepository.getAssetById(assetId);
    }

    public List<Asset> getAssetsById(List<String> assetIds) {
        return assetAsBuiltRepository.getAssetsById(assetIds);
    }

    public Asset getAssetByChildId(String assetId, String childId) {
        return assetAsBuiltRepository.getAssetByChildId(assetId, childId);
    }
}
